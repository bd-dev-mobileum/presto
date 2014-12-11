/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mobileum.presto.metadata;

import com.facebook.presto.spi.ConnectorColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.type.Type;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

import static com.google.common.base.Preconditions.checkNotNull;

public final class MetadataColumnHandle implements ConnectorColumnHandle {
	private final String connectorId;
	private final String columnName;
	private final Type columnType;
	private final int ordinalPosition;

	public MetadataColumnHandle(String connectorId,
			ColumnMetadata columnMetadata) {
		this(connectorId, columnMetadata.getName(), columnMetadata.getType(),
				columnMetadata.getOrdinalPosition());
	}

	@JsonCreator
	public MetadataColumnHandle(
			@JsonProperty("connectorId") String connectorId,
			@JsonProperty("columnName") String columnName,
			@JsonProperty("columnType") Type columnType,
			@JsonProperty("ordinalPosition") int ordinalPosition) {
		this.connectorId = checkNotNull(connectorId, "connectorId is null");
		this.columnName = checkNotNull(columnName, "columnName is null");
		this.columnType = checkNotNull(columnType, "columnType is null");
		this.ordinalPosition = ordinalPosition;
	}

	@JsonProperty
	public String getConnectorId() {
		return connectorId;
	}

	@JsonProperty
	public String getColumnName() {
		return columnName;
	}

	@JsonProperty
	public Type getColumnType() {
		return columnType;
	}

	@JsonProperty
	public int getOrdinalPosition() {
		return ordinalPosition;
	}

	public ColumnMetadata getColumnMetadata() {
		return new ColumnMetadata(columnName, columnType, ordinalPosition,
				false);
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(connectorId, columnName);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if ((obj == null) || (getClass() != obj.getClass())) {
			return false;
		}

		MetadataColumnHandle other = (MetadataColumnHandle) obj;
		return Objects.equal(this.connectorId, other.connectorId)
				&& Objects.equal(this.columnName, other.columnName);
	}

	@Override
	public String toString() {
		return Objects.toStringHelper(this).add("connectorId", connectorId)
				.add("columnName", columnName).add("columnType", columnType)
				.add("ordinalPosition", ordinalPosition).toString();
	}
}
