package com.facebook.presto.hive;

import com.facebook.presto.spi.ImportClient;
import com.facebook.presto.spi.ObjectNotFoundException;
import com.facebook.presto.spi.PartitionChunk;
import com.facebook.presto.spi.PartitionInfo;
import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.SchemaField;
import com.facebook.presto.spi.SchemaField.Type;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.airlift.json.JsonCodec;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.MetaStoreUtils;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.serde.Constants;
import org.apache.hadoop.hive.serde2.ColumnProjectionUtils;
import org.apache.hadoop.hive.serde2.Deserializer;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.columnar.BytesRefArrayWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector.PrimitiveCategory;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorUtils;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.util.ReflectionUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static com.facebook.presto.hive.HadoopConfiguration.HADOOP_CONFIGURATION;
import static com.facebook.presto.hive.HiveColumn.indexGetter;
import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Lists.transform;
import static java.lang.Math.min;
import static org.apache.hadoop.hive.metastore.api.Constants.FILE_INPUT_FORMAT;

@SuppressWarnings("deprecation")
public class HiveClient
        implements ImportClient
{
    private static final int PARTITION_BATCH_SIZE = 1000;

    // TODO: consider injecting these static instances
    private static final ExecutorService PARTITION_LIST_EXECUTOR = Executors.newFixedThreadPool(
            50,
            new ThreadFactoryBuilder()
                    .setNameFormat("hive-client-partition-lister-%d")
                    .setDaemon(true)
                    .build()
    );
    private static final ExecutorService HDFS_LIST_EXECUTOR = Executors.newFixedThreadPool(
            50,
            new ThreadFactoryBuilder()
                    .setNameFormat("hive-client-hdfs-lister-%d")
                    .setDaemon(true)
                    .build()
    );

    private final String metastoreHost;
    private final int metastorePort;
    private final long maxChunkBytes;
    private final JsonCodec<HivePartitionChunk> partitionChunkCodec;

    public HiveClient(String metastoreHost, int metastorePort, long maxChunkBytes, JsonCodec<HivePartitionChunk> partitionChunkCodec)
    {
        this.metastoreHost = metastoreHost;
        this.metastorePort = metastorePort;
        this.maxChunkBytes = maxChunkBytes;
        this.partitionChunkCodec = partitionChunkCodec;

        HadoopNative.requireHadoopNative();
    }

    @Override
    public List<String> getDatabaseNames()
    {
        try (HiveMetastoreClient metastore = getClient()) {
            return metastore.get_all_databases();
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public List<String> getTableNames(String databaseName)
            throws ObjectNotFoundException
    {
        try (HiveMetastoreClient metastore = getClient()) {
            List<String> tables = metastore.get_all_tables(databaseName);
            if (tables.isEmpty()) {
                // Check to see if the database exists
                metastore.get_database(databaseName);
            }
            return tables;
        }
        catch (NoSuchObjectException e) {
            throw new ObjectNotFoundException(e.getMessage());
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public List<SchemaField> getTableSchema(String databaseName, String tableName)
            throws ObjectNotFoundException
    {
        try (HiveMetastoreClient metastore = getClient()) {
            Table table = metastore.get_table(databaseName, tableName);
            List<FieldSchema> partitionKeys = table.getPartitionKeys();
            Properties schema = MetaStoreUtils.getSchema(table);
            return getSchemaFields(schema, partitionKeys);
        }
        catch (NoSuchObjectException e) {
            throw new ObjectNotFoundException(e.getMessage());
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public List<SchemaField> getPartitionKeys(String databaseName, String tableName)
            throws ObjectNotFoundException
    {
        try (HiveMetastoreClient metastore = getClient()) {
            Table table = metastore.get_table(databaseName, tableName);
            List<FieldSchema> partitionKeys = table.getPartitionKeys();

            ImmutableList.Builder<SchemaField> schemaFields = ImmutableList.builder();
            for (int i = 0; i < partitionKeys.size(); i++) {
                FieldSchema field = partitionKeys.get(i);
                Type type = convertHiveType(field.getType());

                // partition keys are always the first fields in the table
                schemaFields.add(SchemaField.createPrimitive(field.getName(), i, type));
            }

            return schemaFields.build();
        }
        catch (NoSuchObjectException e) {
            throw new ObjectNotFoundException(e.getMessage());
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public List<String> getPartitionNames(String databaseName, String tableName)
            throws ObjectNotFoundException
    {
        try (HiveMetastoreClient metastore = getClient()) {
            List<String> partitionNames = metastore.get_partition_names(databaseName, tableName, (short) 0);
            if (partitionNames.isEmpty()) {
                // Check to see if the table exists
                metastore.get_table(databaseName, tableName);
            }
            return partitionNames;
        }
        catch (NoSuchObjectException e) {
            throw new ObjectNotFoundException(e.getMessage());
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public List<PartitionInfo> getPartitions(String databaseName, String tableName, final Map<String, Object> filters)
            throws ObjectNotFoundException
    {
        // build the filtering prefix
        List<String> parts = new ArrayList<>();
        List<SchemaField> partitionKeys = getPartitionKeys(databaseName, tableName);
        for (SchemaField key : partitionKeys) {
            Object value = filters.get(key.getFieldName());

            if (value == null) {
                // we're building a partition prefix, so stop at the first missing binding
                break;
            }

            Preconditions.checkArgument(value instanceof String || value instanceof Double || value instanceof Long,
                    "Only String, Double and Long partition keys are supported");

            parts.add(value.toString());
        }

        // fetch the partition names
        List<PartitionInfo> partitions;
        if (parts.isEmpty()) {
            partitions = getPartitions(databaseName, tableName);
        }
        else {
            try (HiveMetastoreClient metastore = getClient()) {
                List<String> names = metastore.get_partition_names_ps(databaseName, tableName, parts, (short) -1);
                partitions = Lists.transform(names, toPartitionInfo(partitionKeys));
            }
            catch (NoSuchObjectException e) {
                throw new ObjectNotFoundException(e.getMessage());
            }
            catch (Exception e) {
                throw Throwables.propagate(e);
            }
        }

        // do a final pass to filter based on fields that could not be used to build the prefix
        return ImmutableList.copyOf(Iterables.filter(partitions, partitionMatches(filters)));
    }

    @Override
    public List<PartitionInfo> getPartitions(String databaseName, String tableName)
            throws ObjectNotFoundException
    {
        List<SchemaField> partitionKeys = getPartitionKeys(databaseName, tableName);
        return Lists.transform(getPartitionNames(databaseName, tableName), toPartitionInfo(partitionKeys));
    }

    @Override
    public List<PartitionChunk> getPartitionChunks(String databaseName, String tableName, String partitionName, List<String> columns)
            throws ObjectNotFoundException
    {
        try (HiveMetastoreClient metastore = getClient()) {
            Table table = metastore.get_table(databaseName, tableName);
            Partition partition = metastore.get_partition_by_name(databaseName, tableName, partitionName);

            return getPartitionChunks(table, partition, getHiveColumns(table, columns));
        }
        catch (NoSuchObjectException e) {
            throw new ObjectNotFoundException(e.getMessage());
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public Iterable<List<PartitionChunk>> getPartitionChunks(String databaseName, String tableName, List<String> partitionNames, List<String> columns)
            throws ObjectNotFoundException
    {
        Table table = getTable(databaseName, tableName);

        ImmutableList.Builder<Partition> partitionsBuilder = ImmutableList.builder();
        try (HiveMetastoreClient metastore = getClient()) {
            for (List<String> batchedPartitionNames : Lists.partition(partitionNames, PARTITION_BATCH_SIZE)) {
                partitionsBuilder.addAll(metastore.get_partitions_by_names(databaseName, tableName, batchedPartitionNames));
            }
        }
        catch (NoSuchObjectException e) {
            throw new ObjectNotFoundException(e.getMessage());
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }

        int count = 0;
        ImmutableList.Builder<List<PartitionChunk>> builder = ImmutableList.builder();
        ExecutorCompletionService<List<PartitionChunk>> executorCompletionService = new ExecutorCompletionService<>(PARTITION_LIST_EXECUTOR);
        for (Partition partition : partitionsBuilder.build()) {
            count++;
            executorCompletionService.submit(createPartitionCallable(table, partition, getHiveColumns(table, columns)));
        }
        try {
            for (int i = 0; i < count; i++) {
                builder.add(executorCompletionService.take().get());
            }
        }
        catch (InterruptedException | ExecutionException e) {
            throw Throwables.propagate(e);
        }
        return builder.build();
    }

    @Override
    public RecordCursor getRecords(PartitionChunk partitionChunk)
    {
        checkArgument(partitionChunk instanceof HivePartitionChunk,
                "expected instance of %s: %s", HivePartitionChunk.class, partitionChunk.getClass());
        assert partitionChunk instanceof HivePartitionChunk; // // IDEA-60343
        HivePartitionChunk chunk = (HivePartitionChunk) partitionChunk;

        try {
            // Clone schema since we modify it below
            Properties schema = (Properties) chunk.getSchema().clone();

            // We are handling parsing directly since the hive code is slow
            // In order to do this, remove column types entry so that hive treats all columns as type "string"
            String typeSpecification = (String) schema.remove(Constants.LIST_COLUMN_TYPES);
            Preconditions.checkNotNull(typeSpecification, "Partition column type specification is null");

            String nullSequence = (String) schema.get(Constants.SERIALIZATION_NULL_FORMAT);
            checkState(nullSequence == null || nullSequence.equals("\\N"), "Only '\\N' supported as null specifier, was '%s'", nullSequence);

            // Tell hive the columns we would like to read, this lets hive optimize reading column oriented files
            List<HiveColumn> columns = chunk.getColumns();
            if (columns.isEmpty()) {
                // for count(*) queries we will have "no" columns we want to read, but since hive doesn't
                // support no columns (it will read all columns instead), we must choose a single column
                columns = ImmutableList.of(getFirstPrimitiveColumn(schema));
            }
            ColumnProjectionUtils.setReadColumnIDs(HADOOP_CONFIGURATION.get(), new ArrayList<>(transform(columns, indexGetter())));

            RecordReader<?, ?> recordReader = createRecordReader(chunk);
            if (recordReader.createValue() instanceof BytesRefArrayWritable) {
                return new BytesHiveRecordCursor<>((RecordReader<?, BytesRefArrayWritable>) recordReader, chunk.getLength(), chunk.getPartitionKeys(), columns);
            }
            else {
                return new GenericHiveRecordCursor<>((RecordReader<?, ? extends Writable>) recordReader, chunk.getLength(), chunk.getSchema(), chunk.getPartitionKeys(), columns);
            }
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private RecordReader<?, ?> createRecordReader(HivePartitionChunk chunk)
    {
        InputFormat inputFormat = getInputFormat(chunk.getSchema());
        FileSplit split = new FileSplit(chunk.getPath(), chunk.getStart(), chunk.getLength(), (String[]) null);
        JobConf jobConf = new JobConf(HADOOP_CONFIGURATION.get());

        try {
            return inputFormat.getRecordReader(split, jobConf, Reporter.NULL);
        }
        catch (IOException e) {
            throw new RuntimeException("Unable to create record reader for input format " + getInputFormatName(chunk.getSchema()), e);
        }
    }

    private InputFormat getInputFormat(Properties schema)
    {
        String inputFormatName = getInputFormatName(schema);
        try {
            JobConf jobConf = new JobConf(HADOOP_CONFIGURATION.get());

            // This code should be equivalent to jobConf.getInputFormat()
            Class<? extends InputFormat> inputFormatClass = jobConf.getClassByName(inputFormatName).asSubclass(InputFormat.class);
            if (inputFormatClass == null) {
                // default file format in Hadoop is TextInputFormat
                inputFormatClass = TextInputFormat.class;
            }
            return ReflectionUtils.newInstance(inputFormatClass, jobConf);
        }
        catch (Exception e) {
            throw new RuntimeException("Unable to create record reader for input format " + inputFormatName, e);
        }
    }

    @Override
    public byte[] serializePartitionChunk(PartitionChunk partitionChunk)
    {
        checkArgument(partitionChunk instanceof HivePartitionChunk,
                "expected instance of %s: %s", HivePartitionChunk.class, partitionChunk.getClass());
        return partitionChunkCodec.toJson((HivePartitionChunk) partitionChunk).getBytes(UTF_8);
    }

    @Override
    public PartitionChunk deserializePartitionChunk(byte[] bytes)
    {
        return partitionChunkCodec.fromJson(new String(bytes, UTF_8));
    }

    private HiveMetastoreClient getClient()
    {
        try {
            return HiveMetastoreClient.create(metastoreHost, metastorePort);
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private HiveColumn getFirstPrimitiveColumn(Properties schema)
    {
        try {
            Deserializer deserializer = MetaStoreUtils.getDeserializer(null, schema);
            StructObjectInspector rowInspector = (StructObjectInspector) deserializer.getObjectInspector();

            int index = 0;
            for (StructField field : rowInspector.getAllStructFieldRefs()) {
                if (field.getFieldObjectInspector().getCategory() == ObjectInspector.Category.PRIMITIVE) {
                    PrimitiveObjectInspector inspector = (PrimitiveObjectInspector) field.getFieldObjectInspector();
                    SchemaField.Type type = getSupportedPrimitiveType(inspector.getPrimitiveCategory());
                    return new HiveColumn(field.getFieldName(), index, type, inspector.getPrimitiveCategory());
                }
                index++;
            }
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }

        throw new IllegalStateException("Table doesn't have any PRIMITIVE columns");
    }

    private static List<HiveColumn> getHiveColumns(Table table, List<String> columns)
    {
        HashSet<String> columnNames = new HashSet<>(columns);

        // remove primary keys
        for (FieldSchema fieldSchema : table.getPartitionKeys()) {
            columnNames.remove(fieldSchema.getName());
        }

        try {
            Properties schema = MetaStoreUtils.getSchema(table);
            Deserializer deserializer = MetaStoreUtils.getDeserializer(null, schema);
            StructObjectInspector tableInspector = (StructObjectInspector) deserializer.getObjectInspector();

            ImmutableList.Builder<HiveColumn> hiveColumns = ImmutableList.builder();
            int index = 0;
            for (StructField field : tableInspector.getAllStructFieldRefs()) {
                // ignore unused columns
                // remove the columns as we find them so we can know if all columns were found
                if (columnNames.remove(field.getFieldName())) {

                    ObjectInspector fieldInspector = field.getFieldObjectInspector();
                    Preconditions.checkArgument(fieldInspector.getCategory() == Category.PRIMITIVE, "Column %s is not a primitive type", field.getFieldName());
                    PrimitiveObjectInspector inspector = (PrimitiveObjectInspector) fieldInspector;
                    Type type = getSupportedPrimitiveType(inspector.getPrimitiveCategory());
                    PrimitiveCategory hiveType = inspector.getPrimitiveCategory();

                    hiveColumns.add(new HiveColumn(field.getFieldName(), index, type, hiveType));
                }
                index++;
            }

            Preconditions.checkArgument(columnNames.isEmpty(), "Table %s does not contain the columns %s", table.getTableName(), columnNames);

            return hiveColumns.build();
        }
        catch (MetaException | SerDeException e) {
            throw Throwables.propagate(e);
        }
    }

    private Table getTable(String databaseName, String tableName)
            throws ObjectNotFoundException
    {
        try (HiveMetastoreClient metastore = getClient()) {
            return metastore.get_table(databaseName, tableName);
        }
        catch (NoSuchObjectException e) {
            throw new ObjectNotFoundException(e.getMessage());
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private List<PartitionChunk> getPartitionChunks(Table table, Partition partition, List<HiveColumn> columns)
            throws IOException
    {
        Properties schema = MetaStoreUtils.getSchema(partition, table);
        List<HivePartitionKey> partitionKeys = getPartitionKeys(table, partition);

        InputFormat inputFormat = getInputFormat(schema);

        Path partitionLocation = new CachingPath(partition.getSd().getLocation());
        FileSystem fs = partitionLocation.getFileSystem(HADOOP_CONFIGURATION.get());
        ImmutableList.Builder<PartitionChunk> list = ImmutableList.builder();
        List<FileStatus> files = new RecursiveFileSystemTraversal(fs, partitionLocation, HDFS_LIST_EXECUTOR).list();

        for (FileStatus file : files) {
            boolean splittable = isSplittable(inputFormat,
                    file.getPath().getFileSystem(HADOOP_CONFIGURATION.get()),
                    file.getPath());

            long splitSize = splittable ? maxChunkBytes : file.getLen();
            for (long start = 0; start < file.getLen(); start += splitSize) {
                long length = min(splitSize, file.getLen() - start);
                list.add(new HivePartitionChunk(file.getPath(), start, length, schema, partitionKeys, columns));
            }
        }
        return list.build();
    }

    private boolean isSplittable(InputFormat inputFormat, FileSystem fileSystem, Path path)
    {
        // use reflection to get isSplitable method on InputFormat
        Method method = null;
        for (Class<?> clazz = inputFormat.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                method = clazz.getDeclaredMethod("isSplitable", FileSystem.class, Path.class);
                break;
            }
            catch (NoSuchMethodException e) {
            }
        }

        if (method == null) {
            return false;
        }
        try {
            method.setAccessible(true);
            return (boolean) method.invoke(inputFormat, fileSystem, path);
        }
        catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private static Function<String, PartitionInfo> toPartitionInfo(final List<SchemaField> keys)
    {
        return new Function<String, PartitionInfo>()
        {
            @Override
            public PartitionInfo apply(String partitionName)
            {
                try {
                    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
                    List<String> parts = Warehouse.getPartValuesFromPartName(partitionName);
                    for (int i = 0; i < parts.size(); i++) {
                        builder.put(keys.get(i).getFieldName(), parts.get(i));
                    }

                    return new PartitionInfo(partitionName, builder.build());
                }
                catch (MetaException e) {
                    throw Throwables.propagate(e);
                }
            }
        };
    }

    public static final Predicate<PartitionInfo> partitionMatches(final Map<String, Object> filters)
    {
        return new Predicate<PartitionInfo>()
        {
            @Override
            public boolean apply(PartitionInfo partition)
            {
                for (Map.Entry<String, String> entry : partition.getKeyFields().entrySet()) {
                    String partitionKey = entry.getKey();
                    Object filterValue = filters.get(partitionKey);
                    if (filterValue != null && !entry.getValue().equals(filterValue)) {
                        return false;
                    }
                }
                return true;
            }
        };
    }

    private Callable<List<PartitionChunk>> createPartitionCallable(final Table table, final Partition partition, final List<HiveColumn> columns)
    {
        return new Callable<List<PartitionChunk>>()
        {
            @Override
            public List<PartitionChunk> call()
                    throws Exception
            {
                return getPartitionChunks(table, partition, columns);
            }
        };
    }

    private static String getInputFormatName(Properties schema)
    {
        String name = schema.getProperty(FILE_INPUT_FORMAT);
        checkArgument(name != null, "missing property: %s", FILE_INPUT_FORMAT);
        return name;
    }

    private static List<SchemaField> getSchemaFields(Properties schema, List<FieldSchema> partitionKeys)
            throws MetaException, SerDeException
    {
        Deserializer deserializer = MetaStoreUtils.getDeserializer(null, schema);
        ObjectInspector inspector = deserializer.getObjectInspector();
        checkArgument(inspector.getCategory() == ObjectInspector.Category.STRUCT, "expected STRUCT: %s", inspector.getCategory());
        StructObjectInspector structObjectInspector = (StructObjectInspector) inspector;

        ImmutableList.Builder<SchemaField> schemaFields = ImmutableList.builder();

        // add the partition keys
        for (int i = 0; i < partitionKeys.size(); i++) {
            FieldSchema field = partitionKeys.get(i);
            SchemaField.Type type = convertHiveType(field.getType());
            schemaFields.add(SchemaField.createPrimitive(field.getName(), i, type));
        }

        // add the data fields
        List<? extends StructField> fields = structObjectInspector.getAllStructFieldRefs();
        int columnIndex = partitionKeys.size();
        for (StructField field : fields) {
            ObjectInspector fieldInspector = field.getFieldObjectInspector();

            // ignore containers rather than failing
            if (fieldInspector.getCategory() == Category.PRIMITIVE) {
                Type type = getPrimitiveType(((PrimitiveObjectInspector) fieldInspector).getPrimitiveCategory());
                if (type != null) { // ignore unsupported primitive types
                    schemaFields.add(SchemaField.createPrimitive(field.getFieldName(), columnIndex, type));
                }
            }

            columnIndex++;
        }

        return schemaFields.build();
    }

    private static SchemaField.Type getSupportedPrimitiveType(PrimitiveCategory category)
    {
        Type type = getPrimitiveType(category);
        if (type == null) {
            throw new IllegalArgumentException("Hive type not supported: " + category);
        }
        return type;
    }

    private static SchemaField.Type getPrimitiveType(PrimitiveCategory category)
    {
        switch (category) {
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
                return SchemaField.Type.LONG;
            case FLOAT:
            case DOUBLE:
                return SchemaField.Type.DOUBLE;
            case STRING:
                return SchemaField.Type.STRING;
            case BOOLEAN:
                return SchemaField.Type.LONG;
            default:
                return null;
        }
    }

    private static SchemaField.Type convertHiveType(String type)
    {
        return getSupportedPrimitiveType(convertNativeHiveType(type));
    }

    private static PrimitiveCategory convertNativeHiveType(String type)
    {
        return PrimitiveObjectInspectorUtils.getTypeEntryFromTypeName(type).primitiveCategory;
    }

    private static List<HivePartitionKey> getPartitionKeys(Table table, Partition partition)
    {
        ImmutableList.Builder<HivePartitionKey> partitionKeys = ImmutableList.builder();
        List<FieldSchema> keys = table.getPartitionKeys();
        List<String> values = partition.getValues();
        checkArgument(keys.size() == values.size(), "Expected %s partition key values, but got %s", keys.size(), values.size());
        for (int i = 0; i < keys.size(); i++) {
            String name = keys.get(i).getName();
            PrimitiveCategory hiveType = convertNativeHiveType(keys.get(i).getType());
            Type type = getSupportedPrimitiveType(hiveType);
            String value = values.get(i);
            checkNotNull(value, "partition key value cannot be null for field: %s", name);
            partitionKeys.add(new HivePartitionKey(name, type, hiveType, value));
        }
        return partitionKeys.build();
    }

    // TODO: clean this code up
    private static class RecursiveFileSystemTraversal
    {
        private final FileSystem fileSystem;
        private final Path rootPath;
        private final ExecutorCompletionService<Object> executorCompletionService;
        private final Queue<FileStatus> fileStatuses = new LinkedBlockingQueue<>();
        private final AtomicLong taskCount = new AtomicLong(0);

        private RecursiveFileSystemTraversal(FileSystem fileSystem, Path rootPath, ExecutorService executorService)
        {
            this.fileSystem = fileSystem;
            this.rootPath = rootPath;
            this.executorCompletionService = new ExecutorCompletionService<>(executorService);
        }

        private List<FileStatus> list()
        {
            // Launch job
            listRecursively(rootPath);

            // Wait for all jobs to complete
            while (taskCount.get() > 0) {
                try {
                    executorCompletionService.take();
                }
                catch (InterruptedException e) {
                    throw Throwables.propagate(e);
                }
            }
            return ImmutableList.copyOf(fileStatuses);
        }

        private void listRecursively(Path path)
        {
            try {
                FileStatus[] statuses = fileSystem.listStatus(path);
                checkState(statuses != null, "Partition location %s does not exist", path);
                assert statuses != null; // IDEA-60343
                for (final FileStatus status : statuses) {
                    if (status.isDir()) {
                        taskCount.addAndGet(1);
                        executorCompletionService.submit(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                try {
                                    listRecursively(status.getPath());
                                }
                                finally {
                                    taskCount.addAndGet(-1);
                                }
                            }
                        },
                                new Object()
                        );
                    }
                    else {
                        fileStatuses.add(status);
                    }
                }
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }
        }
    }
}
