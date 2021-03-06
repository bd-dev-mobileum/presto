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
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.Session;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.MetadataManager;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.TupleDomain;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.DoubleType;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.VarcharType;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.DependencyExtractor;
import com.facebook.presto.sql.planner.DeterminismEvaluator;
import com.facebook.presto.sql.planner.DomainTranslator;
import com.facebook.presto.sql.planner.EffectivePredicateExtractor;
import com.facebook.presto.sql.planner.EqualityInference;
import com.facebook.presto.sql.planner.ExpressionInterpreter;
import com.facebook.presto.sql.planner.ExpressionSymbolInliner;
import com.facebook.presto.sql.planner.LiteralInterpreter;
import com.facebook.presto.sql.planner.NoOpSymbolResolver;
import com.facebook.presto.sql.planner.PlanNodeIdAllocator;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolAllocator;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.MarkDistinctNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanRewriter;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.SampleNode;
import com.facebook.presto.sql.planner.plan.SemiJoinNode;
import com.facebook.presto.sql.planner.plan.SortNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.UnionNode;
import com.facebook.presto.sql.planner.plan.UnnestNode;
import com.facebook.presto.sql.planner.plan.ValuesNode;
import com.facebook.presto.sql.tree.BooleanLiteral;
import com.facebook.presto.sql.tree.Cast;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.DefaultExpressionTraversalVisitor;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.ExpressionTreeRewriter;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.LongLiteral;
import com.facebook.presto.sql.tree.NullLiteral;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import io.airlift.log.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import static com.facebook.presto.sql.ExpressionUtils.and;
import static com.facebook.presto.sql.ExpressionUtils.combineConjuncts;
import static com.facebook.presto.sql.ExpressionUtils.expressionOrNullSymbols;
import static com.facebook.presto.sql.ExpressionUtils.extractConjuncts;
import static com.facebook.presto.sql.ExpressionUtils.stripNonDeterministicConjuncts;
import static com.facebook.presto.sql.analyzer.ExpressionAnalyzer.getExpressionTypes;
import static com.facebook.presto.sql.planner.DeterminismEvaluator.isDeterministic;
import static com.facebook.presto.sql.planner.EqualityInference.createEqualityInference;
import static com.facebook.presto.sql.planner.plan.JoinNode.Type.CROSS;
import static com.facebook.presto.sql.planner.plan.JoinNode.Type.FULL;
import static com.facebook.presto.sql.planner.plan.JoinNode.Type.INNER;
import static com.facebook.presto.sql.planner.plan.JoinNode.Type.LEFT;
import static com.facebook.presto.sql.planner.plan.JoinNode.Type.RIGHT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;

public class PredicatePushDown
        extends PlanOptimizer
{
    private static final Logger log = Logger.get(PredicatePushDown.class);

    private final Metadata metadata;
    private final SqlParser sqlParser;
	private  CustomizedPredicatePushDownContext customizedPredicatePushDownContext;

    public PredicatePushDown(Metadata metadata, SqlParser sqlParser)
    {
        this.metadata = checkNotNull(metadata, "metadata is null");
        this.sqlParser = checkNotNull(sqlParser, "sqlParser is null");
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, Map<Symbol, Type> types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator)
    {
        checkNotNull(plan, "plan is null");
        checkNotNull(session, "session is null");
        checkNotNull(types, "types is null");
        checkNotNull(idAllocator, "idAllocator is null");

        return PlanRewriter.rewriteWith(new Rewriter(symbolAllocator, idAllocator, metadata, sqlParser, session,getCustomizedPredicatePushDownContext()), plan, PredicatePushDownContext.TRUE_LITERAL());
    }
    private CustomizedPredicatePushDownContext getCustomizedPredicatePushDownContext() {
    	if(customizedPredicatePushDownContext==null){
    		return new CustomizedPredicatePushDownContext();
    	}
		return customizedPredicatePushDownContext;
	}



	public void setCustomizedPredicatePushDownContext(
			CustomizedPredicatePushDownContext customizedPredicatePushDownContext) {
		this.customizedPredicatePushDownContext = customizedPredicatePushDownContext;
	}

    private static class Rewriter
            extends PlanRewriter<PredicatePushDownContext>
    {
        private final SymbolAllocator symbolAllocator;
        private final PlanNodeIdAllocator idAllocator;
        private final Metadata metadata;
        private final SqlParser sqlParser;
        private final Session session;
		private  CustomizedPredicatePushDownContext customizedPredicatePushDownContext;
		private Map<Symbol, Expression> modifiMap = new HashMap<Symbol, Expression>();

        private Rewriter(
                SymbolAllocator symbolAllocator,
                PlanNodeIdAllocator idAllocator,
                Metadata metadata,
                SqlParser sqlParser,
                Session session,
                CustomizedPredicatePushDownContext customizedPredicatePushDownContext)
        {
            this.symbolAllocator = checkNotNull(symbolAllocator, "symbolAllocator is null");
            this.idAllocator = checkNotNull(idAllocator, "idAllocator is null");
            this.metadata = checkNotNull(metadata, "metadata is null");
            this.sqlParser = checkNotNull(sqlParser, "sqlParser is null");
            this.session = checkNotNull(session, "session is null");
			this.customizedPredicatePushDownContext = customizedPredicatePushDownContext;
        }
        
		@Override
        public PlanNode visitPlan(PlanNode node, RewriteContext<PredicatePushDownContext> context)
        {
            PlanNode rewrittenNode = context.defaultRewrite(node, PredicatePushDownContext.TRUE_LITERAL());
            if (!context.get().getExpression().equals(BooleanLiteral.TRUE_LITERAL)) {
                // Drop in a FilterNode b/c we cannot push our predicate down any further
                rewrittenNode = new FilterNode(idAllocator.getNextId(), rewrittenNode, context.get().getExpression());
            }
            return rewrittenNode;
        }
//we don't know about aggregate push down here.
        
        @Override
        public PlanNode visitExchange(ExchangeNode node, RewriteContext<PredicatePushDownContext> context)
        {
            boolean modified = false;
            ImmutableList.Builder<PlanNode> builder = ImmutableList.builder();
            for (int i = 0; i < node.getSources().size(); i++) {
                Map<Symbol, QualifiedNameReference> outputsToInputs = new HashMap<>();
                for (int index = 0; index < node.getInputs().get(i).size(); index++) {
                    outputsToInputs.put(
                            node.getOutputSymbols().get(index),
                            node.getInputs().get(i).get(index).toQualifiedNameReference());
                }

                Expression sourcePredicate = ExpressionTreeRewriter.rewriteWith(new ExpressionSymbolInliner(outputsToInputs), context.get().getExpression());
                PlanNode source = node.getSources().get(i);
                PlanNode rewrittenSource = context.rewrite(source,new PredicatePushDownContext(sourcePredicate));
                if (rewrittenSource != source) {
                    modified = true;
                }
                builder.add(rewrittenSource);
            }

            if (modified) {
                return new ExchangeNode(
                        node.getId(),
                        node.getType(),
                        node.getPartitionKeys(),
                        node.getHashSymbol(),
                        builder.build(),
                        node.getOutputSymbols(),
                        node.getInputs());
            }

            return node;
        }

        @Override
        public PlanNode visitProject(ProjectNode node, RewriteContext<PredicatePushDownContext> context)
        {
        	customizedPredicatePushDownContext.getSymbolToExpressionMap().put(
					node.getId(), node.getAssignments());
            Set<Symbol> deterministicSymbols = node.getAssignments().entrySet().stream()
                    .filter(entry -> DeterminismEvaluator.isDeterministic(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());

            java.util.function.Predicate<Expression> deterministic = conjunct -> DependencyExtractor.extractAll(conjunct).stream()
                    .allMatch(deterministicSymbols::contains);

            Map<Boolean, List<Expression>> conjuncts = extractConjuncts(context.get().getExpression()).stream().collect(Collectors.partitioningBy(deterministic));

            // Push down conjuncts from the inherited predicate that don't depend on non-deterministic assignments
            Expression expression=ExpressionTreeRewriter.rewriteWith(new ExpressionSymbolInliner(node.getAssignments()), combineConjuncts(conjuncts.get(true)));
            PlanNode rewrittenNode =  context.defaultRewrite(node,
                    context.get().setExpression(expression));
			HashMap<Symbol, Expression> hashMap2 = new HashMap<Symbol, Expression>();
			for (Entry<Symbol, Expression> entry : ((ProjectNode)rewrittenNode)
					.getAssignments().entrySet()) {
				if (modifiMap.containsKey(entry.getKey())) {
					hashMap2.put(entry.getKey(), modifiMap.get(entry.getKey()));
				} else {
					hashMap2.put(entry.getKey(), entry.getValue());
				}
			}
				rewrittenNode=new ProjectNode(rewrittenNode.getId(), ((ProjectNode) rewrittenNode).getSource(), hashMap2);

            // All non-deterministic conjuncts, if any, will be in the filter node.
            if (!conjuncts.get(false).isEmpty()) {
                rewrittenNode = new FilterNode(idAllocator.getNextId(), rewrittenNode, combineConjuncts(conjuncts.get(false)));
            }

            return rewrittenNode;
        }

        @Override
        public PlanNode visitMarkDistinct(MarkDistinctNode node, RewriteContext<PredicatePushDownContext> context)
        {
            checkState(!DependencyExtractor.extractUnique(context.get().getExpression()).contains(node.getMarkerSymbol()), "predicate depends on marker symbol");
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitSort(SortNode node, RewriteContext<PredicatePushDownContext> context)
        {
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitUnion(UnionNode node, RewriteContext<PredicatePushDownContext> context)
        {
            boolean modified = false;
            ImmutableList.Builder<PlanNode> builder = ImmutableList.builder();
            for (int i = 0; i < node.getSources().size(); i++) {
                Expression sourcePredicate = ExpressionTreeRewriter.rewriteWith(new ExpressionSymbolInliner(node.sourceSymbolMap(i)), context.get().getExpression());
                PlanNode source = node.getSources().get(i);
                PlanNode rewrittenSource = context.rewrite(source, new PredicatePushDownContext((sourcePredicate)));
                if (rewrittenSource != source) {
                    modified = true;
                }
                builder.add(rewrittenSource);
            }

            if (modified) {
                return new UnionNode(node.getId(), builder.build(), node.getSymbolMapping());
            }

            return node;
        }

        @Override
        public PlanNode visitFilter(FilterNode node, RewriteContext<PredicatePushDownContext> context)
        {
            return context.rewrite(node.getSource(), context.get().setExpression(combineConjuncts(node.getPredicate(), context.get().getExpression())));
        }

        @Override
        public PlanNode visitJoin(JoinNode node, RewriteContext<PredicatePushDownContext> context)
        {
            Expression inheritedPredicate = context.get().getExpression();

            boolean isCrossJoin = (node.getType() == JoinNode.Type.CROSS);

            // See if we can rewrite outer joins in terms of a plain inner join
            node = tryNormalizeToInnerJoin(node, inheritedPredicate);

            Expression leftEffectivePredicate = EffectivePredicateExtractor.extract(node.getLeft(), symbolAllocator.getTypes());
            Expression rightEffectivePredicate = EffectivePredicateExtractor.extract(node.getRight(), symbolAllocator.getTypes());
            Expression joinPredicate = extractJoinPredicate(node);

            Expression leftPredicate;
            Expression rightPredicate;
            Expression postJoinPredicate;
            Expression newJoinPredicate;

            switch (node.getType()) {
                case INNER:
                    InnerJoinPushDownResult innerJoinPushDownResult = processInnerJoin(inheritedPredicate,
                            leftEffectivePredicate,
                            rightEffectivePredicate,
                            joinPredicate,
                            node.getLeft().getOutputSymbols());
                    leftPredicate = innerJoinPushDownResult.getLeftPredicate();
                    rightPredicate = innerJoinPushDownResult.getRightPredicate();
                    postJoinPredicate = innerJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = innerJoinPushDownResult.getJoinPredicate();
                    break;
                case LEFT:
                    OuterJoinPushDownResult leftOuterJoinPushDownResult = processLimitedOuterJoin(inheritedPredicate,
                            leftEffectivePredicate,
                            rightEffectivePredicate,
                            joinPredicate,
                            node.getLeft().getOutputSymbols());
                    leftPredicate = leftOuterJoinPushDownResult.getOuterJoinPredicate();
                    rightPredicate = leftOuterJoinPushDownResult.getInnerJoinPredicate();
                    postJoinPredicate = leftOuterJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = joinPredicate; // Use the same as the original
                    break;
                case RIGHT:
                    OuterJoinPushDownResult rightOuterJoinPushDownResult = processLimitedOuterJoin(inheritedPredicate,
                            rightEffectivePredicate,
                            leftEffectivePredicate,
                            joinPredicate,
                            node.getRight().getOutputSymbols());
                    leftPredicate = rightOuterJoinPushDownResult.getInnerJoinPredicate();
                    rightPredicate = rightOuterJoinPushDownResult.getOuterJoinPredicate();
                    postJoinPredicate = rightOuterJoinPushDownResult.getPostJoinPredicate();
                    newJoinPredicate = joinPredicate; // Use the same as the original
                    break;
                case FULL:
                    leftPredicate = BooleanLiteral.TRUE_LITERAL;
                    rightPredicate = BooleanLiteral.TRUE_LITERAL;
                    postJoinPredicate = inheritedPredicate;
                    newJoinPredicate = joinPredicate;
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported join type: " + node.getType());
            }

            PlanNode leftSource = context.rewrite(node.getLeft(), new PredicatePushDownContext(leftPredicate));
            PlanNode rightSource = context.rewrite(node.getRight(),new PredicatePushDownContext( rightPredicate));

            PlanNode output = node;
            if (leftSource != node.getLeft() || rightSource != node.getRight() || !newJoinPredicate.equals(joinPredicate) || isCrossJoin) {
                List<JoinNode.EquiJoinClause> criteria = node.getCriteria();

                // Rewrite criteria and add projections if there is a new join predicate

                if (!newJoinPredicate.equals(joinPredicate) || isCrossJoin) {
                    // Create identity projections for all existing symbols
                    ImmutableMap.Builder<Symbol, Expression> leftProjections = ImmutableMap.builder();

                    leftProjections.putAll(node.getLeft()
                            .getOutputSymbols().stream()
                            .collect(Collectors.toMap(key -> key, Symbol::toQualifiedNameReference)));

                    ImmutableMap.Builder<Symbol, Expression> rightProjections = ImmutableMap.builder();
                    rightProjections.putAll(node.getRight()
                            .getOutputSymbols().stream()
                            .collect(Collectors.toMap(key -> key, Symbol::toQualifiedNameReference)));

                    // HACK! we don't support cross joins right now, so put in a simple fake join predicate instead if all of the join clauses got simplified out
                    // TODO: remove this code when cross join support is added
                    Iterable<Expression> simplifiedJoinConjuncts = transform(extractConjuncts(newJoinPredicate), this::simplifyExpression);
                    simplifiedJoinConjuncts = filter(simplifiedJoinConjuncts, not(Predicates.<Expression>equalTo(BooleanLiteral.TRUE_LITERAL)));
                    if (Iterables.isEmpty(simplifiedJoinConjuncts)) {
                        simplifiedJoinConjuncts = ImmutableList.<Expression>of(new ComparisonExpression(ComparisonExpression.Type.EQUAL, new LongLiteral("0"), new LongLiteral("0")));
                    }

                    // Create new projections for the new join clauses
                    ImmutableList.Builder<JoinNode.EquiJoinClause> builder = ImmutableList.builder();
                    for (Expression conjunct : simplifiedJoinConjuncts) {
                        checkState(joinEqualityExpression(node.getLeft().getOutputSymbols()).apply(conjunct), "Expected join predicate to be a valid join equality");

                        ComparisonExpression equality = (ComparisonExpression) conjunct;

                        boolean alignedComparison = Iterables.all(DependencyExtractor.extractUnique(equality.getLeft()), in(node.getLeft().getOutputSymbols()));
                        Expression leftExpression = (alignedComparison) ? equality.getLeft() : equality.getRight();
                        Expression rightExpression = (alignedComparison) ? equality.getRight() : equality.getLeft();

                        Symbol leftSymbol = symbolAllocator.newSymbol(leftExpression, extractType(leftExpression));
                        leftProjections.put(leftSymbol, leftExpression);
                        Symbol rightSymbol = symbolAllocator.newSymbol(rightExpression, extractType(rightExpression));
                        rightProjections.put(rightSymbol, rightExpression);

                        builder.add(new JoinNode.EquiJoinClause(leftSymbol, rightSymbol));
                    }

                    leftSource = new ProjectNode(idAllocator.getNextId(), leftSource, leftProjections.build());
                    rightSource = new ProjectNode(idAllocator.getNextId(), rightSource, rightProjections.build());
                    criteria = builder.build();
                }
                output = new JoinNode(node.getId(), node.getType(), leftSource, rightSource, criteria, node.getLeftHashSymbol(), node.getRightHashSymbol(),node.getComparisons());
            }
            if (!postJoinPredicate.equals(BooleanLiteral.TRUE_LITERAL)) {
                output = new FilterNode(idAllocator.getNextId(), output, postJoinPredicate);
            }
            return output;
        }

        private OuterJoinPushDownResult processLimitedOuterJoin(Expression inheritedPredicate, Expression outerEffectivePredicate, Expression innerEffectivePredicate, Expression joinPredicate, Collection<Symbol> outerSymbols)
        {
            checkArgument(Iterables.all(DependencyExtractor.extractUnique(outerEffectivePredicate), in(outerSymbols)), "outerEffectivePredicate must only contain symbols from outerSymbols");
            checkArgument(Iterables.all(DependencyExtractor.extractUnique(innerEffectivePredicate), not(in(outerSymbols))), "innerEffectivePredicate must not contain symbols from outerSymbols");

            ImmutableList.Builder<Expression> outerPushdownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<Expression> innerPushdownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<Expression> postJoinConjuncts = ImmutableList.builder();

            // Strip out non-deterministic conjuncts
            postJoinConjuncts.addAll(filter(extractConjuncts(inheritedPredicate), not(DeterminismEvaluator::isDeterministic)));
            inheritedPredicate = stripNonDeterministicConjuncts(inheritedPredicate);

            outerEffectivePredicate = stripNonDeterministicConjuncts(outerEffectivePredicate);
            innerEffectivePredicate = stripNonDeterministicConjuncts(innerEffectivePredicate);
            joinPredicate = stripNonDeterministicConjuncts(joinPredicate);

            // Generate equality inferences
            EqualityInference inheritedInference = createEqualityInference(inheritedPredicate);
            EqualityInference outerInference = createEqualityInference(inheritedPredicate, outerEffectivePredicate);

            EqualityInference.EqualityPartition equalityPartition = inheritedInference.generateEqualitiesPartitionedBy(in(outerSymbols));
            Expression outerOnlyInheritedEqualities = combineConjuncts(equalityPartition.getScopeEqualities());
            EqualityInference potentialNullSymbolInference = createEqualityInference(outerOnlyInheritedEqualities, outerEffectivePredicate, innerEffectivePredicate, joinPredicate);
            EqualityInference potentialNullSymbolInferenceWithoutInnerInferred = createEqualityInference(outerOnlyInheritedEqualities, outerEffectivePredicate, joinPredicate);

            // Sort through conjuncts in inheritedPredicate that were not used for inference
            for (Expression conjunct : EqualityInference.nonInferrableConjuncts(inheritedPredicate)) {
                Expression outerRewritten = outerInference.rewriteExpression(conjunct, in(outerSymbols));
                if (outerRewritten != null) {
                    outerPushdownConjuncts.add(outerRewritten);

                    // A conjunct can only be pushed down into an inner side if it can be rewritten in terms of the outer side
                    Expression innerRewritten = potentialNullSymbolInference.rewriteExpression(outerRewritten, not(in(outerSymbols)));
                    if (innerRewritten != null) {
                        innerPushdownConjuncts.add(innerRewritten);
                    }
                }
                else {
                    postJoinConjuncts.add(conjunct);
                }
            }

            // See if we can push down any outer or join predicates to the inner side
            for (Expression conjunct : EqualityInference.nonInferrableConjuncts(and(outerEffectivePredicate, joinPredicate))) {
                Expression rewritten = potentialNullSymbolInference.rewriteExpression(conjunct, not(in(outerSymbols)));
                if (rewritten != null) {
                    innerPushdownConjuncts.add(rewritten);
                }
            }

            // TODO: consider adding join predicate optimizations to outer joins

            // Add the equalities from the inferences back in
            outerPushdownConjuncts.addAll(equalityPartition.getScopeEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());
            innerPushdownConjuncts.addAll(potentialNullSymbolInferenceWithoutInnerInferred.generateEqualitiesPartitionedBy(not(in(outerSymbols))).getScopeEqualities());

            return new OuterJoinPushDownResult(combineConjuncts(outerPushdownConjuncts.build()),
                    combineConjuncts(innerPushdownConjuncts.build()),
                    combineConjuncts(postJoinConjuncts.build()));
        }

        private static class OuterJoinPushDownResult
        {
            private final Expression outerJoinPredicate;
            private final Expression innerJoinPredicate;
            private final Expression postJoinPredicate;

            private OuterJoinPushDownResult(Expression outerJoinPredicate, Expression innerJoinPredicate, Expression postJoinPredicate)
            {
                this.outerJoinPredicate = outerJoinPredicate;
                this.innerJoinPredicate = innerJoinPredicate;
                this.postJoinPredicate = postJoinPredicate;
            }

            private Expression getOuterJoinPredicate()
            {
                return outerJoinPredicate;
            }

            private Expression getInnerJoinPredicate()
            {
                return innerJoinPredicate;
            }

            private Expression getPostJoinPredicate()
            {
                return postJoinPredicate;
            }
        }

        private InnerJoinPushDownResult processInnerJoin(Expression inheritedPredicate, Expression leftEffectivePredicate, Expression rightEffectivePredicate, Expression joinPredicate, Collection<Symbol> leftSymbols)
        {
            checkArgument(Iterables.all(DependencyExtractor.extractUnique(leftEffectivePredicate), in(leftSymbols)), "leftEffectivePredicate must only contain symbols from leftSymbols");
            checkArgument(Iterables.all(DependencyExtractor.extractUnique(rightEffectivePredicate), not(in(leftSymbols))), "rightEffectivePredicate must not contain symbols from leftSymbols");

            ImmutableList.Builder<Expression> leftPushDownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<Expression> rightPushDownConjuncts = ImmutableList.builder();
            ImmutableList.Builder<Expression> joinConjuncts = ImmutableList.builder();

            // Strip out non-deterministic conjuncts
            joinConjuncts.addAll(filter(extractConjuncts(inheritedPredicate), not(DeterminismEvaluator::isDeterministic)));
            inheritedPredicate = stripNonDeterministicConjuncts(inheritedPredicate);

            joinConjuncts.addAll(filter(extractConjuncts(joinPredicate), not(DeterminismEvaluator::isDeterministic)));
            joinPredicate = stripNonDeterministicConjuncts(joinPredicate);

            leftEffectivePredicate = stripNonDeterministicConjuncts(leftEffectivePredicate);
            rightEffectivePredicate = stripNonDeterministicConjuncts(rightEffectivePredicate);

            // Generate equality inferences
            EqualityInference allInference = createEqualityInference(inheritedPredicate, leftEffectivePredicate, rightEffectivePredicate, joinPredicate);
            EqualityInference allInferenceWithoutLeftInferred = createEqualityInference(inheritedPredicate, rightEffectivePredicate, joinPredicate);
            EqualityInference allInferenceWithoutRightInferred = createEqualityInference(inheritedPredicate, leftEffectivePredicate, joinPredicate);

            // Sort through conjuncts in inheritedPredicate that were not used for inference
            for (Expression conjunct : EqualityInference.nonInferrableConjuncts(inheritedPredicate)) {
                Expression leftRewrittenConjunct = allInference.rewriteExpression(conjunct, in(leftSymbols));
                if (leftRewrittenConjunct != null) {
                    leftPushDownConjuncts.add(leftRewrittenConjunct);
                }

                Expression rightRewrittenConjunct = allInference.rewriteExpression(conjunct, not(in(leftSymbols)));
                if (rightRewrittenConjunct != null) {
                    rightPushDownConjuncts.add(rightRewrittenConjunct);
                }

                // Drop predicate after join only if unable to push down to either side
                if (leftRewrittenConjunct == null && rightRewrittenConjunct == null) {
                    joinConjuncts.add(conjunct);
                }
            }

            // See if we can push the right effective predicate to the left side
            for (Expression conjunct : EqualityInference.nonInferrableConjuncts(rightEffectivePredicate)) {
                Expression rewritten = allInference.rewriteExpression(conjunct, in(leftSymbols));
                if (rewritten != null) {
                    leftPushDownConjuncts.add(rewritten);
                }
            }

            // See if we can push the left effective predicate to the right side
            for (Expression conjunct : EqualityInference.nonInferrableConjuncts(leftEffectivePredicate)) {
                Expression rewritten = allInference.rewriteExpression(conjunct, not(in(leftSymbols)));
                if (rewritten != null) {
                    rightPushDownConjuncts.add(rewritten);
                }
            }

            // See if we can push any parts of the join predicates to either side
            for (Expression conjunct : EqualityInference.nonInferrableConjuncts(joinPredicate)) {
                Expression leftRewritten = allInference.rewriteExpression(conjunct, in(leftSymbols));
                if (leftRewritten != null) {
                    leftPushDownConjuncts.add(leftRewritten);
                }

                Expression rightRewritten = allInference.rewriteExpression(conjunct, not(in(leftSymbols)));
                if (rightRewritten != null) {
                    rightPushDownConjuncts.add(rightRewritten);
                }

                if (leftRewritten == null && rightRewritten == null) {
                    joinConjuncts.add(conjunct);
                }
            }

            // Add equalities from the inference back in
            leftPushDownConjuncts.addAll(allInferenceWithoutLeftInferred.generateEqualitiesPartitionedBy(in(leftSymbols)).getScopeEqualities());
            rightPushDownConjuncts.addAll(allInferenceWithoutRightInferred.generateEqualitiesPartitionedBy(not(in(leftSymbols))).getScopeEqualities());
            joinConjuncts.addAll(allInference.generateEqualitiesPartitionedBy(in(leftSymbols)).getScopeStraddlingEqualities()); // scope straddling equalities get dropped in as part of the join predicate

            // Since we only currently support equality in join conjuncts, factor out the non-equality conjuncts to a post-join filter
            List<Expression> joinConjunctsList = joinConjuncts.build();
            List<Expression> postJoinConjuncts = ImmutableList.copyOf(filter(joinConjunctsList, not(joinEqualityExpression(leftSymbols))));
            joinConjunctsList = ImmutableList.copyOf(filter(joinConjunctsList, joinEqualityExpression(leftSymbols)));

            return new InnerJoinPushDownResult(combineConjuncts(leftPushDownConjuncts.build()), combineConjuncts(rightPushDownConjuncts.build()), combineConjuncts(joinConjunctsList), combineConjuncts(postJoinConjuncts));
        }

        private static class InnerJoinPushDownResult
        {
            private final Expression leftPredicate;
            private final Expression rightPredicate;
            private final Expression joinPredicate;
            private final Expression postJoinPredicate;

            private InnerJoinPushDownResult(Expression leftPredicate, Expression rightPredicate, Expression joinPredicate, Expression postJoinPredicate)
            {
                this.leftPredicate = leftPredicate;
                this.rightPredicate = rightPredicate;
                this.joinPredicate = joinPredicate;
                this.postJoinPredicate = postJoinPredicate;
            }

            private Expression getLeftPredicate()
            {
                return leftPredicate;
            }

            private Expression getRightPredicate()
            {
                return rightPredicate;
            }

            private Expression getJoinPredicate()
            {
                return joinPredicate;
            }

            private Expression getPostJoinPredicate()
            {
                return postJoinPredicate;
            }
        }

        private static Expression extractJoinPredicate(JoinNode joinNode)
        {
            ImmutableList.Builder<Expression> builder = ImmutableList.builder();
            for (JoinNode.EquiJoinClause equiJoinClause : joinNode.getCriteria()) {
                builder.add(equalsExpression(equiJoinClause.getLeft(), equiJoinClause.getRight()));
            }
            return combineConjuncts(builder.build());
        }

        private static Expression equalsExpression(Symbol symbol1, Symbol symbol2)
        {
            return new ComparisonExpression(ComparisonExpression.Type.EQUAL,
                    new QualifiedNameReference(symbol1.toQualifiedName()),
                    new QualifiedNameReference(symbol2.toQualifiedName()));
        }

        private Type extractType(Expression expression)
        {
            return getExpressionTypes(session, metadata, sqlParser, symbolAllocator.getTypes(), expression).get(expression);
        }

        private JoinNode tryNormalizeToInnerJoin(JoinNode node, Expression inheritedPredicate)
        {
            Preconditions.checkArgument(EnumSet.of(INNER, RIGHT, LEFT, FULL, CROSS).contains(node.getType()), "Unsupported join type: %s", node.getType());

            if (node.getType() == JoinNode.Type.CROSS) {
                return new JoinNode(node.getId(), JoinNode.Type.INNER, node.getLeft(), node.getRight(), node.getCriteria(), node.getLeftHashSymbol(), node.getRightHashSymbol(),node.getComparisons());
            }

            if (node.getType() == JoinNode.Type.FULL) {
                boolean canConvertToLeftJoin = canConvertOuterToInner(node.getLeft().getOutputSymbols(), inheritedPredicate);
                boolean canConvertToRightJoin = canConvertOuterToInner(node.getRight().getOutputSymbols(), inheritedPredicate);
                if (!canConvertToLeftJoin && !canConvertToRightJoin) {
                    return node;
                }
                if (canConvertToLeftJoin && canConvertToRightJoin) {
                    return new JoinNode(node.getId(), INNER, node.getLeft(), node.getRight(), node.getCriteria(), node.getLeftHashSymbol(), node.getRightHashSymbol(),node.getComparisons());
                }
                else {
                    return new JoinNode(node.getId(), canConvertToLeftJoin ? LEFT : RIGHT,
                            node.getLeft(), node.getRight(), node.getCriteria(), node.getLeftHashSymbol(), node.getRightHashSymbol(),node.getComparisons());
                }
            }

            if (node.getType() == JoinNode.Type.INNER ||
                    node.getType() == JoinNode.Type.LEFT && !canConvertOuterToInner(node.getRight().getOutputSymbols(), inheritedPredicate) ||
                    node.getType() == JoinNode.Type.RIGHT && !canConvertOuterToInner(node.getLeft().getOutputSymbols(), inheritedPredicate)) {
                return node;
            }
            return new JoinNode(node.getId(), JoinNode.Type.INNER, node.getLeft(), node.getRight(), node.getCriteria(), node.getLeftHashSymbol(), node.getRightHashSymbol(),node.getComparisons());
        }

        private boolean canConvertOuterToInner(List<Symbol> innerSymbolsForOuterJoin, Expression inheritedPredicate)
        {
            Set<Symbol> innerSymbols = ImmutableSet.copyOf(innerSymbolsForOuterJoin);
            for (Expression conjunct : extractConjuncts(inheritedPredicate)) {
                if (DeterminismEvaluator.isDeterministic(conjunct)) {
                    // Ignore a conjunct for this test if we can not deterministically get responses from it
                    Object response = nullInputEvaluator(innerSymbols, conjunct);
                    if (response == null || response instanceof NullLiteral || Boolean.FALSE.equals(response)) {
                        // If there is a single conjunct that returns FALSE or NULL given all NULL inputs for the inner side symbols of an outer join
                        // then this conjunct removes all effects of the outer join, and effectively turns this into an equivalent of an inner join.
                        // So, let's just rewrite this join as an INNER join
                        return true;
                    }
                }
            }
            return false;
        }

        // Temporary implementation for joins because the SimplifyExpressions optimizers can not run properly on join clauses
        private Expression simplifyExpression(Expression expression)
        {
            IdentityHashMap<Expression, Type> expressionTypes = getExpressionTypes(session, metadata, sqlParser, symbolAllocator.getTypes(), expression);
            ExpressionInterpreter optimizer = ExpressionInterpreter.expressionOptimizer(expression, metadata, session, expressionTypes);
            return LiteralInterpreter.toExpression(optimizer.optimize(NoOpSymbolResolver.INSTANCE), expressionTypes.get(expression));
        }

        /**
         * Evaluates an expression's response to binding the specified input symbols to NULL
         */
        private Object nullInputEvaluator(final Collection<Symbol> nullSymbols, Expression expression)
        {
            IdentityHashMap<Expression, Type> expressionTypes = getExpressionTypes(session, metadata, sqlParser, symbolAllocator.getTypes(), expression);
            return ExpressionInterpreter.expressionOptimizer(expression, metadata, session, expressionTypes)
                    .optimize(symbol -> nullSymbols.contains(symbol) ? null : new QualifiedNameReference(symbol.toQualifiedName()));
        }

        private static Predicate<Expression> joinEqualityExpression(final Collection<Symbol> leftSymbols)
        {
            return expression -> {
                // At this point in time, our join predicates need to be deterministic
                if (isDeterministic(expression) && expression instanceof ComparisonExpression) {
                    ComparisonExpression comparison = (ComparisonExpression) expression;
                    if (comparison.getType() == ComparisonExpression.Type.EQUAL) {
                        Set<Symbol> symbols1 = DependencyExtractor.extractUnique(comparison.getLeft());
                        Set<Symbol> symbols2 = DependencyExtractor.extractUnique(comparison.getRight());
                        return (Iterables.all(symbols1, in(leftSymbols)) && Iterables.all(symbols2, not(in(leftSymbols)))) ||
                                (Iterables.all(symbols2, in(leftSymbols)) && Iterables.all(symbols1, not(in(leftSymbols))));
                    }
                }
                return false;
            };
        }

        @Override
        public PlanNode visitSemiJoin(SemiJoinNode node, RewriteContext<PredicatePushDownContext> context)
        {
            Expression inheritedPredicate = context.get().getExpression();

            Expression sourceEffectivePredicate = EffectivePredicateExtractor.extract(node.getSource(), symbolAllocator.getTypes());

            List<Expression> sourceConjuncts = new ArrayList<>();
            List<Expression> filteringSourceConjuncts = new ArrayList<>();
            List<Expression> postJoinConjuncts = new ArrayList<>();

            // TODO: see if there are predicates that can be inferred from the semi join output

            // Push inherited and source predicates to filtering source via a contrived join predicate (but needs to avoid touching NULL values in the filtering source)
            Expression joinPredicate = equalsExpression(node.getSourceJoinSymbol(), node.getFilteringSourceJoinSymbol());
            EqualityInference joinInference = createEqualityInference(inheritedPredicate, sourceEffectivePredicate, joinPredicate);
            for (Expression conjunct : Iterables.concat(EqualityInference.nonInferrableConjuncts(inheritedPredicate), EqualityInference.nonInferrableConjuncts(sourceEffectivePredicate))) {
                Expression rewrittenConjunct = joinInference.rewriteExpression(conjunct, equalTo(node.getFilteringSourceJoinSymbol()));
                if (rewrittenConjunct != null && DeterminismEvaluator.isDeterministic(rewrittenConjunct)) {
                    // Alter conjunct to include an OR filteringSourceJoinSymbol IS NULL disjunct
                    Expression rewrittenConjunctOrNull = expressionOrNullSymbols(equalTo(node.getFilteringSourceJoinSymbol())).apply(rewrittenConjunct);
                    filteringSourceConjuncts.add(rewrittenConjunctOrNull);
                }
            }
            EqualityInference.EqualityPartition joinInferenceEqualityPartition = joinInference.generateEqualitiesPartitionedBy(equalTo(node.getFilteringSourceJoinSymbol()));
            filteringSourceConjuncts.addAll(ImmutableList.copyOf(transform(joinInferenceEqualityPartition.getScopeEqualities(),
                    expressionOrNullSymbols(equalTo(node.getFilteringSourceJoinSymbol())))));

            // Push inheritedPredicates down to the source if they don't involve the semi join output
            EqualityInference inheritedInference = createEqualityInference(inheritedPredicate);
            for (Expression conjunct : EqualityInference.nonInferrableConjuncts(inheritedPredicate)) {
                Expression rewrittenConjunct = inheritedInference.rewriteExpression(conjunct, in(node.getSource().getOutputSymbols()));
                // Since each source row is reflected exactly once in the output, ok to push non-deterministic predicates down
                if (rewrittenConjunct != null) {
                    sourceConjuncts.add(rewrittenConjunct);
                }
                else {
                    postJoinConjuncts.add(conjunct);
                }
            }

            // Add the inherited equality predicates back in
            EqualityInference.EqualityPartition equalityPartition = inheritedInference.generateEqualitiesPartitionedBy(in(node.getSource().getOutputSymbols()));
            sourceConjuncts.addAll(equalityPartition.getScopeEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postJoinConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());

            PlanNode rewrittenSource = context.rewrite(node.getSource(), new PredicatePushDownContext(combineConjuncts(sourceConjuncts)));
            PlanNode rewrittenFilteringSource = context.rewrite(node.getFilteringSource(), new PredicatePushDownContext(combineConjuncts(filteringSourceConjuncts)));

            PlanNode output = node;
            if (rewrittenSource != node.getSource() || rewrittenFilteringSource != node.getFilteringSource()) {
                output = new SemiJoinNode(node.getId(), rewrittenSource, rewrittenFilteringSource, node.getSourceJoinSymbol(), node.getFilteringSourceJoinSymbol(), node.getSemiJoinOutput(), node.getSourceHashSymbol(), node.getFilteringSourceHashSymbol());
            }
            if (!postJoinConjuncts.isEmpty()) {
                output = new FilterNode(idAllocator.getNextId(), output, combineConjuncts(postJoinConjuncts));
            }
            return output;
        }

		Map<Symbol, List<Symbol>> mappings = new HashMap<Symbol, List<Symbol>>();
		Map<FunctionCall, FunctionCall> symbolToColumnAggregation;
        @Override
        public PlanNode visitAggregation(AggregationNode node, RewriteContext<PredicatePushDownContext> context)
        {
        	try {
				symbolToColumnAggregation = AggregateFunctionSymbolMappingResolver
						.resolve(node, customizedPredicatePushDownContext
								.getSymbolToExpressionMap());
				// }
			} catch (Exception e) {
				symbolToColumnAggregation = null;
			}
        	List<Symbol> testList = new ArrayList<Symbol>();
			testList = node.getOutputSymbols();
			
            Expression inheritedPredicate = context.get().getExpression();

            EqualityInference equalityInference = createEqualityInference(inheritedPredicate);

            List<Expression> pushdownConjuncts = new ArrayList<>();
            List<Expression> postAggregationConjuncts = new ArrayList<>();

            // Strip out non-deterministic conjuncts
            postAggregationConjuncts.addAll(ImmutableList.copyOf(filter(extractConjuncts(inheritedPredicate), not(DeterminismEvaluator::isDeterministic))));
            inheritedPredicate = stripNonDeterministicConjuncts(inheritedPredicate);

            // Sort non-equality predicates by those that can be pushed down and those that cannot
            for (Expression conjunct : EqualityInference.nonInferrableConjuncts(inheritedPredicate)) {
                Expression rewrittenConjunct = equalityInference.rewriteExpression(conjunct, in(node.getGroupBy()));
                if (rewrittenConjunct != null) {
                    pushdownConjuncts.add(rewrittenConjunct);
                }
                else {
                    postAggregationConjuncts.add(conjunct);
                }
            }

            // Add the equality predicates back in
            EqualityInference.EqualityPartition equalityPartition = equalityInference.generateEqualitiesPartitionedBy(in(node.getGroupBy()));
            pushdownConjuncts.addAll(equalityPartition.getScopeEqualities());
            postAggregationConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postAggregationConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());

			Map<Symbol, FunctionCall> aggregations = node.getAggregations();
			List<Symbol> groupBy = node.getGroupBy();
//			context.get().setGroupBy(groupBy);
//			context.get().setAggregations(aggregations);
//			context.get().setFunctionMap(node.getFunctions());
//			context.get().setExpression(combineConjuncts(pushdownConjuncts));
			PredicatePushDownContext predicatePushDownContext=new PredicatePushDownContext(combineConjuncts(pushdownConjuncts));
			predicatePushDownContext.setAggregations(aggregations);
			predicatePushDownContext.setGroupBy(groupBy);
			predicatePushDownContext.setFunctionMap(node.getFunctions());
            PlanNode rewrittenSource = context.rewrite(node.getSource(), predicatePushDownContext);

            PlanNode output = node;
            if (predicatePushDownContext.getAggregations() != null
					&& predicatePushDownContext.getGroupBy() != null
					&& predicatePushDownContext.getFunctionMap() != null
					&& (predicatePushDownContext.getFunctionMap() != node.getFunctions()
						|| predicatePushDownContext.getAggregations() != node.getAggregations() 
						|| predicatePushDownContext.getGroupBy() != node.getGroupBy())) {
				output = new AggregationNode(node.getId(), rewrittenSource,
						predicatePushDownContext.getGroupBy(),
						predicatePushDownContext.getAggregations(),
						predicatePushDownContext.getFunctionMap(),
						node.getMasks(), node.getStep(),
						node.getSampleWeight(),
						node.getConfidence(),
						node.getHashSymbol());
			} else if  (rewrittenSource != node.getSource()) {
                output = new AggregationNode(node.getId(),
                        rewrittenSource,
                        node.getGroupBy(),
                        node.getAggregations(),
                        node.getFunctions(),
                        node.getMasks(),
                        node.getStep(),
                        node.getSampleWeight(),
                        node.getConfidence(),
                        node.getHashSymbol());
            }
            
            List<Symbol> testList2 = output.getOutputSymbols();
			boolean matched = true;
			for (Symbol symbol : testList) {
				if (!testList2.contains(symbol)) {
					matched = false;
				}
			}
			boolean replaceOutput=true;
			if (!matched) {
				Map<Symbol, Expression> hashMap2 = new HashMap<Symbol, Expression>();

				for (Symbol s : node.getOutputSymbols()) {
					if (output.getOutputSymbols().contains(s)) {
						hashMap2.put(s,
								new QualifiedNameReference(s.toQualifiedName()));
					} else {
						if(!modifiMap.containsKey(s)){
							System.out.println("ERROR:Possible missing case for: Symbol "+s+" aggregates:"+node.getAggregations());
							replaceOutput=false;
							break;
						}
						hashMap2.put(s, modifiMap.get(s));
					}
				}
				if(replaceOutput){
				output = new ProjectNode(idAllocator.getNextId(), output,
						hashMap2);
				}
			}
            if (!postAggregationConjuncts.isEmpty()) {
                output = new FilterNode(idAllocator.getNextId(), output, combineConjuncts(postAggregationConjuncts));
            }
            return output;
        }

        @Override
        public PlanNode visitUnnest(UnnestNode node, RewriteContext<PredicatePushDownContext> context)
        {
            Expression inheritedPredicate = context.get().getExpression();

            EqualityInference equalityInference = createEqualityInference(inheritedPredicate);

            List<Expression> pushdownConjuncts = new ArrayList<>();
            List<Expression> postUnnestConjuncts = new ArrayList<>();

            // Strip out non-deterministic conjuncts
            postUnnestConjuncts.addAll(ImmutableList.copyOf(filter(extractConjuncts(inheritedPredicate), not(DeterminismEvaluator::isDeterministic))));
            inheritedPredicate = stripNonDeterministicConjuncts(inheritedPredicate);

            // Sort non-equality predicates by those that can be pushed down and those that cannot
            for (Expression conjunct : EqualityInference.nonInferrableConjuncts(inheritedPredicate)) {
                Expression rewrittenConjunct = equalityInference.rewriteExpression(conjunct, in(node.getReplicateSymbols()));
                if (rewrittenConjunct != null) {
                    pushdownConjuncts.add(rewrittenConjunct);
                }
                else {
                    postUnnestConjuncts.add(conjunct);
                }
            }

            // Add the equality predicates back in
            EqualityInference.EqualityPartition equalityPartition = equalityInference.generateEqualitiesPartitionedBy(in(node.getReplicateSymbols()));
            pushdownConjuncts.addAll(equalityPartition.getScopeEqualities());
            postUnnestConjuncts.addAll(equalityPartition.getScopeComplementEqualities());
            postUnnestConjuncts.addAll(equalityPartition.getScopeStraddlingEqualities());

            PlanNode rewrittenSource = context.rewrite(node.getSource(), new PredicatePushDownContext( combineConjuncts(pushdownConjuncts)));

            PlanNode output = node;
            if (rewrittenSource != node.getSource()) {
                output = new UnnestNode(node.getId(), rewrittenSource, node.getReplicateSymbols(), node.getUnnestSymbols(), node.getOrdinalitySymbol());
            }
            if (!postUnnestConjuncts.isEmpty()) {
                output = new FilterNode(idAllocator.getNextId(), output, combineConjuncts(postUnnestConjuncts));
            }
            return output;
        }

        @Override
        public PlanNode visitSample(SampleNode node, RewriteContext<PredicatePushDownContext> context)
        {
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitTableScan(TableScanNode node, RewriteContext<PredicatePushDownContext> context)
        {
        	long time=System.currentTimeMillis();
        	PredicatePushDownContext predicatePushDownContext = customizedPredicatePushDownContext
					.getPushDownPredicateMap().get(node.getId());
        	Map<FunctionCall,FunctionCall> newFunctionCallVsPreviousFunctionCall=customizedPredicatePushDownContext
        			.getNewFunctionCallVsPreviousFunctionCall().get(node.getId());
        	if(newFunctionCallVsPreviousFunctionCall==null){
        		newFunctionCallVsPreviousFunctionCall=new HashMap<FunctionCall,FunctionCall>();
        		customizedPredicatePushDownContext.getNewFunctionCallVsPreviousFunctionCall().put(node.getId(), 
        				newFunctionCallVsPreviousFunctionCall);
        	}
			if (predicatePushDownContext != null && customizedPredicatePushDownContext.isPredicatePushdownable()) {
				context.get().setExpression(predicatePushDownContext
						.getExpression());
//				context.get().setAggregations(predicatePushDownContext
//						.getAggregations());
//				context.get().setFunctionMap(predicatePushDownContext
//						.getFunctionMap());
//				context.get().setGroupBy(predicatePushDownContext
//						.getGroupBy());
			}
			PredicatePushDownContext inheritedPredicate = context.get();
            Expression predicate = simplifyExpression(context.get().getExpression());

			Iterable<Expression> pushDownAggregationListIterable = null;
			Iterable<Symbol> groupByIterable = null;
			Set<Symbol> distinctColumns = new HashSet<Symbol>();
			// should be push down group by columns to proteum
			Set<Expression> pushDownAggregationList = new HashSet<Expression>();
			boolean shouldPushDownAggregation = false;
			boolean isModified = false;
			boolean weUnderStandAllAggregations = true;
			Map<Symbol, FunctionCall> newMap = new HashMap<Symbol, FunctionCall>();
			Map<Symbol, Signature> functionMap = new HashMap<Symbol, Signature>();
			Map<Symbol, Expression> modifiTempMap = new HashMap<Symbol, Expression>();
			final Map<Symbol, QualifiedNameReference> symbolToColumnName = new HashMap<Symbol, QualifiedNameReference>();
			for (Map.Entry<Symbol, ColumnHandle> entry : node
					.getAssignments().entrySet()) {
				symbolToColumnName.put(
						entry.getKey(),
						new QualifiedNameReference(new QualifiedName(
								metadata.getColumnMetadata(
										node.getTable(),
										entry.getValue()).getName())));
			}
			// global variable ends
			try {
				if ((inheritedPredicate.getAggregations() != null && !inheritedPredicate
						.getAggregations().isEmpty())
						|| (inheritedPredicate.getGroupBy() != null && !inheritedPredicate
								.getGroupBy().isEmpty())) {
					for (Map.Entry<Symbol, ColumnHandle> entry : node
							.getAssignments().entrySet()) {
						symbolToColumnName.put(
								entry.getKey(),
								new QualifiedNameReference(new QualifiedName(
										metadata.getColumnMetadata(
												node.getTable(),
												entry.getValue()).getName())));
					}

					Function<Expression, Expression> ff = new Function<Expression, Expression>() {
						@Override
						public Expression apply(Expression expression) {
							return ExpressionTreeRewriter.rewriteWith(
									new ExpressionSymbolInliner(
											symbolToColumnName), expression);
						}
					};

					Function<Symbol, Symbol> syFunction = new Function<Symbol, Symbol>() {
						@Override
						public Symbol apply(Symbol symbol) {
							if(!symbolToColumnName.containsKey(symbol)){
								throw new RuntimeException("Column not found for symbol :"+symbol);
							}
							return new Symbol(symbolToColumnName.get(symbol)
									.getName().toString());
						}
					};

					List<Expression> aggregateList = new ArrayList<Expression>();
					aggregateList.addAll(inheritedPredicate.getAggregations()
							.values());
					Iterable<Expression> simplifiedAggregates = transform(
							aggregateList, ff);
					// System.out.println(simplifiedAggregates);
					boolean error=false;
					try{
					groupByIterable = transform(
							inheritedPredicate.getGroupBy(), syFunction);
					}catch(Exception e){
						error=true;
					}
					// System.out.println(groupByIterable);
					if(!customizedPredicatePushDownContext.isPredicatePushdownable() || !session.getCatalog().equals("proteum")){
						error=true;
					}
					if (!error && inheritedPredicate.getGroupBy() != null) {
						shouldPushDownAggregation = true;
						for (Entry<Symbol, FunctionCall> entry : inheritedPredicate
								.getAggregations().entrySet()) {
							FunctionCall columnNamedfunctioncall = symbolToColumnAggregation
									.get(entry.getValue());
							System.out.println(entry.getValue()
									+ " is Resolved as "
									+ columnNamedfunctioncall);
							if (containsAnyAggregates(groupByIterable,
											entry.getValue())) {
								shouldPushDownAggregation = false;
								System.out.println("Unable to push down aggregate: "+entry.getValue()+
										" is in group by.");
								break;
							} else if (entry.getValue().isDistinct()
									&& isContainsOnlyOneColumn(entry.getValue())
									&& (entry.getValue().getArguments().get(0) instanceof QualifiedNameReference)
									&& isAloneOverAllOtherAggregations(entry
											.getValue())
									&& !containsAnyAggregates(groupByIterable,
											entry.getValue())
									&& !areArgumentsExpression(entry.getValue())) {
								// Distinct columns here.

								Symbol tempSymbol = new Symbol(
										((QualifiedNameReference) entry
												.getValue().getArguments()
												.get(0)).getName().toString());
								QualifiedNameReference qualifiedNameReference = symbolToColumnName
										.get(tempSymbol);
								if (qualifiedNameReference == null) {
									shouldPushDownAggregation = false;
								} else {
									distinctColumns
											.add(Symbol
													.fromQualifiedName(qualifiedNameReference
															.getName()));
									shouldPushDownAggregation = true;
									newMap.put(entry.getKey(), entry.getValue());
									functionMap.put(entry.getKey(),
											inheritedPredicate.getFunctionMap()
													.get(entry.getKey()));
								}
							}else if(!entry.getValue().isDistinct()
									&& (entry.getValue().getName().toString()
									.equalsIgnoreCase("MIN")||entry.getValue().getName().toString()
									.equalsIgnoreCase("MAX"))
							&& isContainsOnlyOneColumn(entry.getValue())
							&& (entry.getValue().getArguments().get(0) instanceof QualifiedNameReference)
							&& isContainsOnlyOneBigIntVarcharOrDoubleColumn(
									entry.getValue(), symbolAllocator)
							&& isAloneOverAllOtherAggregations(entry
									.getValue())
							&& !containsAnyAggregates(groupByIterable,
									entry.getValue())){

								 shouldPushDownAggregation = true;
	
									pushDownAggregationList
											.add(symbolToColumnAggregation
													.get(entry.getValue()));
								if (areArgumentsExpression(entry.getValue())) {
									modifiTempMap
											.put(new Symbol(
													((QualifiedNameReference) entry
															.getValue()
															.getArguments()
															.get(0)).getName()
															.toString()),
													new QualifiedNameReference(
															getArgumentsAsSymbolList(
																	entry.getValue())
																	.get(0)
																	.toQualifiedName()));
									Symbol symbol = new Symbol(
											((QualifiedNameReference) entry
													.getValue().getArguments()
													.get(0)).getName()
													.toString());
									Type type = symbolAllocator.getTypes().get(
											symbol);
									if (type.getDisplayName().equalsIgnoreCase(
											"double")) {
										modifiTempMap
												.put(symbol,
														new Cast(
																new QualifiedNameReference(
																		getArgumentsAsSymbolList(
																				entry.getValue())
																				.get(0)
																				.toQualifiedName()),
																DoubleType.DOUBLE
																		.getTypeSignature()
																		.toString()));
										newMap.put(entry.getKey(),
												entry.getValue());
										functionMap
												.put(entry.getKey(),
														inheritedPredicate
																.getFunctionMap()
																.get(entry
																		.getKey()));
									} else {
										newMap.put(entry.getKey(),
												entry.getValue());
										functionMap
												.put(entry.getKey(),
														inheritedPredicate
																.getFunctionMap()
																.get(entry
																		.getKey()));
									}
									isModified = true;
								}else{
									newMap.put(entry.getKey(), entry.getValue());
									functionMap.put(entry.getKey(),
											inheritedPredicate.getFunctionMap()
													.get(entry.getKey()));
								}
							
							} else if (!entry.getValue().isDistinct()
									&& entry.getValue().getName().toString()
											.equalsIgnoreCase("SUM")
									&& isContainsOnlyOneColumn(entry.getValue())
									&& (entry.getValue().getArguments().get(0) instanceof QualifiedNameReference)
									&& isContainsOnlyOneBigIntColumn(
											entry.getValue(), symbolAllocator)
									&& isAloneOverAllOtherAggregations(entry
											.getValue())
									&& !containsAnyAggregates(groupByIterable,
											entry.getValue())) {
								 shouldPushDownAggregation = true;
								if (newFunctionCallVsPreviousFunctionCall.containsKey(entry.getValue())) {
									pushDownAggregationList
											.add(changeSumFunctionToCount(symbolToColumnAggregation
													.get(entry.getValue())));
								} else {
									pushDownAggregationList
											.add(symbolToColumnAggregation
													.get(entry.getValue()));
								}
								if (areArgumentsExpression(entry.getValue())) {
									modifiTempMap
											.put(new Symbol(
													((QualifiedNameReference) entry
															.getValue()
															.getArguments()
															.get(0)).getName()
															.toString()),
													new QualifiedNameReference(
															getArgumentsAsSymbolList(
																	entry.getValue())
																	.get(0)
																	.toQualifiedName()));
									Symbol symbol = new Symbol(
											((QualifiedNameReference) entry
													.getValue().getArguments()
													.get(0)).getName()
													.toString());
									Type type = symbolAllocator.getTypes().get(
											symbol);
									if (type.getDisplayName().equalsIgnoreCase(
											"double")) {
										modifiTempMap
												.put(symbol,
														new Cast(
																new QualifiedNameReference(
																		getArgumentsAsSymbolList(
																				entry.getValue())
																				.get(0)
																				.toQualifiedName()),
																DoubleType.DOUBLE
																		.getTypeSignature()
																		.toString()));
										newMap.put(entry.getKey(),
												entry.getValue());
										functionMap
												.put(entry.getKey(),
														inheritedPredicate
																.getFunctionMap()
																.get(entry
																		.getKey()));
									} else {
										newMap.put(entry.getKey(),
												entry.getValue());
										functionMap
												.put(entry.getKey(),
														inheritedPredicate
																.getFunctionMap()
																.get(entry
																		.getKey()));
									}
									isModified = true;
								}else{
									newMap.put(entry.getKey(), entry.getValue());
									functionMap.put(entry.getKey(),
											inheritedPredicate.getFunctionMap()
													.get(entry.getKey()));
								}
							} else if (!entry.getValue().isDistinct()
									&& entry.getValue().getName().toString()
											.equalsIgnoreCase("SUM")
									&& isContainsOnlyTwoColumn(entry.getValue())
									&& (entry.getValue().getArguments().get(0) instanceof QualifiedNameReference)
									&& isContainsOnlyTwoBigIntColumn(
											entry.getValue(), symbolAllocator)
									&& isAloneOverAllOtherAggregations(entry
											.getValue())
									&& !containsAnyAggregates(groupByIterable,
											entry.getValue())) {
								// shouldPushDownAggregation = true;
								// if
								// (entry.getKey().getName().contains("count"))
								// {
								// pushDownAggregationList
								// .add(changeSumFunctionToCount(symbolToColumnAggregation
								// .get(entry.getValue())));
								// } else {
								pushDownAggregationList
										.add(symbolToColumnAggregation
												.get(entry.getValue()));
								// }
								if (areArgumentsExpression(entry.getValue())) {
									modifiTempMap
											.put(new Symbol(
													((QualifiedNameReference) entry
															.getValue()
															.getArguments()
															.get(0)).getName()
															.toString()),
													new QualifiedNameReference(
															getArgumentsAsSymbolList(
																	entry.getValue())
																	.get(0)
																	.toQualifiedName()));
									isModified = true;
								}
								newMap.put(entry.getKey(), entry.getValue());
								functionMap.put(entry.getKey(),
										inheritedPredicate.getFunctionMap()
												.get(entry.getKey()));
							} else if (!entry.getValue().isDistinct()
									&& entry.getValue().getName().toString()
											.equalsIgnoreCase("Count")
									&& isContainsOnlyOneColumn(entry.getValue())
									&& (entry.getValue().getArguments().get(0) instanceof QualifiedNameReference)
									&& isContainsOnlyOneBigIntColumn(
											entry.getValue(), symbolAllocator)
									&& isAloneOverAllOtherAggregations(entry
											.getValue())
									&& !containsAnyAggregates(groupByIterable,
											entry.getValue())
									&& !areArgumentsExpression(entry.getValue())) {
								Symbol s = symbolAllocator.newSymbol(entry
										.getKey().getName(), BigintType.BIGINT);
								FunctionCall functionCall = new FunctionCall(
										new QualifiedName("sum"), entry
												.getValue().getArguments());
								newMap.put(s, functionCall);
								Symbol sss = new Symbol(
										((QualifiedNameReference) entry
												.getValue().getArguments()
												.get(0)).getName().toString());
								String[] arr = new String[] { symbolAllocator
										.getTypes().get(sss).getDisplayName() };
								// System.out.println(arr);
								functionMap
										.put(s, new Signature("sum", "bigint",
												java.util.Arrays.asList(arr)));
								modifiTempMap
										.put(entry.getKey(),
												new QualifiedNameReference(
														new QualifiedName(s
																.getName())));
								isModified = true;
								shouldPushDownAggregation = true;
								pushDownAggregationList.add(entry.getValue());
								newFunctionCallVsPreviousFunctionCall.put(functionCall, entry.getValue());
								// System.out.println(entry.getKey() + " "
								// + entry.getValue());
							} else {
								// newMap.put(entry.getKey(), entry.getValue());
								// functionMap.put(entry.getKey(),
								// inheritedPredicate
								// .getFunctionMap().get(entry.getKey()));
								// Sorry We don,t understand the case : Let's
								// presto
								// handle everything
								shouldPushDownAggregation = false;
								break;
							}
						}
					}
					if (shouldPushDownAggregation) {
						pushDownAggregationListIterable = transform(
								pushDownAggregationList, ff);
					}
				}
				System.out.println("******************************");
				if (shouldPushDownAggregation) {
					try{
					List<Symbol> groupByPushDownable = Lists
							.newArrayList(groupByIterable);
					groupByPushDownable.addAll(distinctColumns);
					groupByIterable = groupByPushDownable;
					System.out.println("PushDownAggregationList is "
							+ pushDownAggregationListIterable);
					System.out.println("PushDownGroupBy is " + groupByIterable);
					}catch(Exception e){
						shouldPushDownAggregation=false;
						System.out.println("Nothing to pushDown.Exception :"+e.getMessage());
					}
				} else {
					if(customizedPredicatePushDownContext.isPredicatePushdownable()){
					System.out.println("Nothing to pushDown");
					}
				}

			} catch (Exception e) {
				System.out.println("Unable to Push Down Group By.Exception: "
						+ e.getMessage());
				shouldPushDownAggregation=false;
			}
			
			ProteumTupleDomain<ColumnHandle> proteumTupleDomain2 = new ProteumTupleDomain<ColumnHandle>(
					java.util.Collections.emptyMap(), 
					ExpressionTreeRewriter.rewriteWith(new ExpressionSymbolInliner(symbolToColumnName), predicate));
			TupleDomain<ColumnHandle> proteumTupleDomain = proteumTupleDomain2;
			if (pushDownAggregationListIterable != null
					&& groupByIterable != null && shouldPushDownAggregation) {
				proteumTupleDomain2.setGroupBy(groupByIterable);
				proteumTupleDomain2
						.setAggregateList(pushDownAggregationListIterable);
				proteumTupleDomain2.setMinimumExpression(ExpressionTreeRewriter.rewriteWith
						(new ExpressionSymbolInliner(symbolToColumnName),FilterRewriter
						.remainingAfterRemovingAggregateFilterExpression(
								symbolAllocator.getTypes(), groupByIterable,
								symbolToColumnName,
								inheritedPredicate.getExpression())));
			}
			if(shouldPushDownAggregation){
			((MetadataManager)metadata).isAggregatePushDownable(node.getTable(), Optional.of(proteumTupleDomain));
			}else{
				proteumTupleDomain2.setAggregatePushDownable(false);
			}
			Expression postScanPredicate=predicate;
			Expression allExpression = postScanPredicate;

			if (proteumTupleDomain2.isAggregatePushDownable()
					&& shouldPushDownAggregation) {
				if (isModified) {
					inheritedPredicate.setAggregations(newMap);
					inheritedPredicate.setFunctionMap(functionMap);
					modifiMap.putAll(modifiTempMap);
				}
				System.out
						.println("Actual Filter is "
								+ ExpressionTreeRewriter.rewriteWith
								(new ExpressionSymbolInliner(symbolToColumnName),postScanPredicate)
								+ " Aggregate filter is "
								+ ExpressionTreeRewriter.rewriteWith
								(new ExpressionSymbolInliner(symbolToColumnName),FilterRewriter.getAggregateFilterExpression(
										symbolAllocator.getTypes(),
										groupByIterable, symbolToColumnName,
										postScanPredicate))
								+ " Remaining is "
								+ ExpressionTreeRewriter.rewriteWith
								(new ExpressionSymbolInliner(symbolToColumnName),FilterRewriter
										.remainingAfterRemovingAggregateFilterExpression(
												symbolAllocator.getTypes(),
												groupByIterable,
												symbolToColumnName,
												postScanPredicate)));
				Expression postScanPredicate2 = postScanPredicate;
				postScanPredicate = FilterRewriter
						.getAggregateFilterExpression(
								symbolAllocator.getTypes(), groupByIterable,
								symbolToColumnName, postScanPredicate);
				System.out.println("Changed Filter from " + ExpressionTreeRewriter.rewriteWith
						(new ExpressionSymbolInliner(symbolToColumnName),postScanPredicate2)
						+ " to " + ExpressionTreeRewriter.rewriteWith
						(new ExpressionSymbolInliner(symbolToColumnName),postScanPredicate));
				predicate=postScanPredicate;
			}
			System.out.println("******************************");
			if (proteumTupleDomain2.isAggregatePushDownable()
					&& shouldPushDownAggregation) {
				customizedPredicatePushDownContext.getPushDownPredicateMap()
						.put(node.getId(),
								new PredicatePushDownContext(allExpression,
										inheritedPredicate));
			}else{
				proteumTupleDomain2.setMinimumExpression(BooleanLiteral.TRUE_LITERAL);
			}
			log.info("Time taken in Predicate Push Down is : "+(System.currentTimeMillis()-time));
			node.setProteumTupleDomain(proteumTupleDomain2);
            if (BooleanLiteral.FALSE_LITERAL.equals(predicate) || predicate instanceof NullLiteral) {
                return new ValuesNode(idAllocator.getNextId(), node.getOutputSymbols(), ImmutableList.of());
            }
            else if (!BooleanLiteral.TRUE_LITERAL.equals(predicate)) {
                return new FilterNode(idAllocator.getNextId(), node, predicate);
            }

            return node;
        }
    	private List<Symbol> getArgumentsAsSymbolList(FunctionCall value) {
			final List<Symbol> columns = new ArrayList<Symbol>();
			symbolToColumnAggregation
					.get(value)
					.accept(new DefaultExpressionTraversalVisitor<List<Symbol>, List<Symbol>>() {
						@Override
						protected List<Symbol> visitQualifiedNameReference(
								QualifiedNameReference node,
								List<Symbol> columns) {
							columns.add(new Symbol(node.getName().toString()));
							return columns;
						}
					}, columns);
			return columns;
		}

		private boolean areArgumentsExpression(FunctionCall value) {
			if (symbolToColumnAggregation.get(value).equals(value)) {
				return false;
			} else {
				return true;
			}
		}

		private boolean isContainsOnlyOneBigIntColumn(FunctionCall value,
				SymbolAllocator symbolAllocator) {
			final List<Symbol> columns = new ArrayList<Symbol>();
			symbolToColumnAggregation
					.get(value)
					.accept(new DefaultExpressionTraversalVisitor<List<Symbol>, List<Symbol>>() {
						@Override
						protected List<Symbol> visitQualifiedNameReference(
								QualifiedNameReference node,
								List<Symbol> columns) {
							columns.add(new Symbol(node.getName().toString()));
							return columns;
						}
					}, columns);
			if (columns.size() == 1) {
				Symbol columnName = columns.get(0);
				return symbolAllocator.getTypes().get(columnName)
						.getDisplayName().equals(BigintType.BIGINT.toString());
			}
			return false;
		}
		
		private boolean isContainsOnlyOneBigIntVarcharOrDoubleColumn(FunctionCall value,
				SymbolAllocator symbolAllocator) {
			final List<Symbol> columns = new ArrayList<Symbol>();
			symbolToColumnAggregation
					.get(value)
					.accept(new DefaultExpressionTraversalVisitor<List<Symbol>, List<Symbol>>() {
						@Override
						protected List<Symbol> visitQualifiedNameReference(
								QualifiedNameReference node,
								List<Symbol> columns) {
							columns.add(new Symbol(node.getName().toString()));
							return columns;
						}
					}, columns);
			if (columns.size() == 1) {
				Symbol columnName = columns.get(0);
				String typeName= symbolAllocator.getTypes().get(columnName)
						.getDisplayName();
				if(typeName.equals(BigintType.BIGINT.toString()) ||
						typeName.equals(DoubleType.DOUBLE.toString()) ||
						typeName.equals(VarcharType.VARCHAR.toString())  ){
					return true;
				}
			}
			return false;
		}

		private boolean isContainsOnlyTwoBigIntColumn(FunctionCall value,
				SymbolAllocator symbolAllocator) {
			final List<Symbol> columns = new ArrayList<Symbol>();
			symbolToColumnAggregation
					.get(value)
					.accept(new DefaultExpressionTraversalVisitor<List<Symbol>, List<Symbol>>() {
						@Override
						protected List<Symbol> visitQualifiedNameReference(
								QualifiedNameReference node,
								List<Symbol> columns) {
							columns.add(new Symbol(node.getName().toString()));
							return columns;
						}
					}, columns);
			if (columns.size() == 2) {
				Symbol columnName = columns.get(0);
				boolean ret = symbolAllocator.getTypes().get(columnName)
						.getDisplayName().equals(BigintType.BIGINT.toString());
				if (!ret) {
					return ret;
				}
				columnName = columns.get(1);
				return symbolAllocator.getTypes().get(columnName)
						.getDisplayName().equals(BigintType.BIGINT.toString());
			}
			return false;
		}

		private boolean isAloneOverAllOtherAggregations(FunctionCall value) {
			Map<FunctionCall, Set<Symbol>> functionCallToResolvedColumnName = new HashMap<FunctionCall, Set<Symbol>>();
			for (FunctionCall key : symbolToColumnAggregation.keySet()) {
				final Set<Symbol> columns = new HashSet<Symbol>();
				symbolToColumnAggregation
						.get(key)
						.accept(new DefaultExpressionTraversalVisitor<Set<Symbol>, Set<Symbol>>() {
							@Override
							protected Set<Symbol> visitQualifiedNameReference(
									QualifiedNameReference node,
									Set<Symbol> columns) {
								columns.add(new Symbol(node.getName()
										.toString()));
								return columns;
							}
						}, columns);
				functionCallToResolvedColumnName.put(key, columns);
			}
			Set<Symbol> set = new HashSet<Symbol>();
			for (Entry<FunctionCall, Set<Symbol>> map : functionCallToResolvedColumnName
					.entrySet()) {
				set.clear();
				set.addAll(functionCallToResolvedColumnName.get(value));
				if (map.getKey().equals(value)) {
					continue;
				}
				set.retainAll(map.getValue());
				if (set.size() > 0) {
					return false;
				}
			}
			return true;
		}

		private boolean isContainsOnlyOneColumn(FunctionCall value) {

			final List<Symbol> columns = new ArrayList<Symbol>();
			symbolToColumnAggregation
					.get(value)
					.accept(new DefaultExpressionTraversalVisitor<List<Symbol>, List<Symbol>>() {
						@Override
						protected List<Symbol> visitQualifiedNameReference(
								QualifiedNameReference node,
								List<Symbol> columns) {
							columns.add(new Symbol(node.getName().toString()));
							return columns;
						}
					}, columns);
			if (columns.size() == 1) {
				return true;
			}
			return false;
		}

		private boolean isContainsOnlyTwoColumn(FunctionCall value) {

			final List<Symbol> columns = new ArrayList<Symbol>();
			symbolToColumnAggregation
					.get(value)
					.accept(new DefaultExpressionTraversalVisitor<List<Symbol>, List<Symbol>>() {
						@Override
						protected List<Symbol> visitQualifiedNameReference(
								QualifiedNameReference node,
								List<Symbol> columns) {
							columns.add(new Symbol(node.getName().toString()));
							return columns;
						}
					}, columns);
			if (columns.size() == 2) {
				return true;
			}
			return false;
		}

		private FunctionCall changeSumFunctionToCount(FunctionCall value) {
			if (value.getName().toString().equalsIgnoreCase("sum")) {
				return new FunctionCall(new QualifiedName("count"),
						value.getArguments());
			} else {
				return value;
			}
		}

		private boolean containsAnyAggregates(
				Iterable<Symbol> simplifiedAggregates, FunctionCall value) {
			final List<Symbol> columns = new ArrayList<Symbol>();
			symbolToColumnAggregation
					.get(value)
					.accept(new DefaultExpressionTraversalVisitor<List<Symbol>, List<Symbol>>() {
						@Override
						protected List<Symbol> visitQualifiedNameReference(
								QualifiedNameReference node,
								List<Symbol> columns) {
							columns.add(new Symbol(node.getName().toString()));
							return columns;
						}
					}, columns);
			Set<Symbol> hashSet = new HashSet<Symbol>();
			for (Symbol symbol : simplifiedAggregates) {
				hashSet.add(symbol);
			}
			for (Symbol s : columns) {
				if (hashSet.contains(s)) {
					return true;
				}
			}
			return false;
		}

		private boolean isOnly(Symbol symbol,
				Iterable<Expression> simplifiedAggregates,
				Map<Symbol, QualifiedNameReference> map6) {
			boolean found = false;
			if (map6.get(symbol) == null) {
				return false;
			}
			for (Expression expression : simplifiedAggregates) {
				FunctionCall functionCall = (FunctionCall) expression;
				for (Expression e : functionCall.getArguments()) {
					if (e instanceof QualifiedNameReference) {
						QualifiedNameReference qualifiedNameReference = (QualifiedNameReference) e;
						if (qualifiedNameReference
								.getName()
								.toString()
								.equalsIgnoreCase(
										map6.get(symbol).getName().toString())) {
							if (!found) {
								found = true;
							} else {
								return false;
							}
						}
					}
				}
			}
			return true;
		}

    }
}