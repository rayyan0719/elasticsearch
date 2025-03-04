/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.parser;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.elasticsearch.dissect.DissectException;
import org.elasticsearch.dissect.DissectParser;
import org.elasticsearch.xpack.esql.parser.EsqlBaseParser.QualifiedNamePatternContext;
import org.elasticsearch.xpack.esql.plan.logical.Dissect;
import org.elasticsearch.xpack.esql.plan.logical.Drop;
import org.elasticsearch.xpack.esql.plan.logical.Enrich;
import org.elasticsearch.xpack.esql.plan.logical.EsqlUnresolvedRelation;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Explain;
import org.elasticsearch.xpack.esql.plan.logical.Grok;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.Keep;
import org.elasticsearch.xpack.esql.plan.logical.MvExpand;
import org.elasticsearch.xpack.esql.plan.logical.Rename;
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.esql.plan.logical.show.ShowFunctions;
import org.elasticsearch.xpack.esql.plan.logical.show.ShowInfo;
import org.elasticsearch.xpack.ql.expression.Alias;
import org.elasticsearch.xpack.ql.expression.Attribute;
import org.elasticsearch.xpack.ql.expression.EmptyAttribute;
import org.elasticsearch.xpack.ql.expression.Expression;
import org.elasticsearch.xpack.ql.expression.Expressions;
import org.elasticsearch.xpack.ql.expression.Literal;
import org.elasticsearch.xpack.ql.expression.MetadataAttribute;
import org.elasticsearch.xpack.ql.expression.NamedExpression;
import org.elasticsearch.xpack.ql.expression.Order;
import org.elasticsearch.xpack.ql.expression.ReferenceAttribute;
import org.elasticsearch.xpack.ql.expression.UnresolvedAttribute;
import org.elasticsearch.xpack.ql.expression.UnresolvedStar;
import org.elasticsearch.xpack.ql.parser.ParserUtils;
import org.elasticsearch.xpack.ql.plan.TableIdentifier;
import org.elasticsearch.xpack.ql.plan.logical.Aggregate;
import org.elasticsearch.xpack.ql.plan.logical.Filter;
import org.elasticsearch.xpack.ql.plan.logical.Limit;
import org.elasticsearch.xpack.ql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.ql.plan.logical.OrderBy;
import org.elasticsearch.xpack.ql.tree.Source;
import org.elasticsearch.xpack.ql.type.DataTypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.elasticsearch.common.logging.HeaderWarning.addWarning;
import static org.elasticsearch.xpack.esql.plan.logical.Enrich.Mode;
import static org.elasticsearch.xpack.ql.parser.ParserUtils.source;
import static org.elasticsearch.xpack.ql.parser.ParserUtils.typedParsing;
import static org.elasticsearch.xpack.ql.parser.ParserUtils.visitList;

public class LogicalPlanBuilder extends ExpressionBuilder {

    public LogicalPlanBuilder(Map<Token, TypedParamValue> params) {
        super(params);
    }

    protected LogicalPlan plan(ParseTree ctx) {
        return ParserUtils.typedParsing(this, ctx, LogicalPlan.class);
    }

    protected List<LogicalPlan> plans(List<? extends ParserRuleContext> ctxs) {
        return ParserUtils.visitList(this, ctxs, LogicalPlan.class);
    }

    @Override
    public LogicalPlan visitSingleStatement(EsqlBaseParser.SingleStatementContext ctx) {
        return plan(ctx.query());
    }

    @Override
    public LogicalPlan visitCompositeQuery(EsqlBaseParser.CompositeQueryContext ctx) {
        LogicalPlan input = plan(ctx.query());
        PlanFactory makePlan = typedParsing(this, ctx.processingCommand(), PlanFactory.class);
        return makePlan.apply(input);
    }

    @Override
    public PlanFactory visitEvalCommand(EsqlBaseParser.EvalCommandContext ctx) {
        return p -> new Eval(source(ctx), p, visitFields(ctx.fields()));
    }

    @Override
    public PlanFactory visitGrokCommand(EsqlBaseParser.GrokCommandContext ctx) {
        return p -> {
            String pattern = visitString(ctx.string()).fold().toString();
            Grok result = new Grok(source(ctx), p, expression(ctx.primaryExpression()), Grok.pattern(source(ctx), pattern));
            return result;
        };
    }

    @Override
    public PlanFactory visitDissectCommand(EsqlBaseParser.DissectCommandContext ctx) {
        return p -> {
            String pattern = visitString(ctx.string()).fold().toString();
            Map<String, Object> options = visitCommandOptions(ctx.commandOptions());
            String appendSeparator = "";
            for (Map.Entry<String, Object> item : options.entrySet()) {
                if (item.getKey().equalsIgnoreCase("append_separator") == false) {
                    throw new ParsingException(source(ctx), "Invalid option for dissect: [{}]", item.getKey());
                }
                if (item.getValue() instanceof String == false) {
                    throw new ParsingException(
                        source(ctx),
                        "Invalid value for dissect append_separator: expected a string, but was [{}]",
                        item.getValue()
                    );
                }
                appendSeparator = (String) item.getValue();
            }
            Source src = source(ctx);

            try {
                DissectParser parser = new DissectParser(pattern, appendSeparator);
                Set<String> referenceKeys = parser.referenceKeys();
                if (referenceKeys.size() > 0) {
                    throw new ParsingException(
                        src,
                        "Reference keys not supported in dissect patterns: [%{*{}}]",
                        referenceKeys.iterator().next()
                    );
                }
                List<Attribute> keys = new ArrayList<>();
                for (var x : parser.outputKeys()) {
                    if (x.isEmpty() == false) {
                        keys.add(new ReferenceAttribute(src, x, DataTypes.KEYWORD));
                    }
                }
                return new Dissect(src, p, expression(ctx.primaryExpression()), new Dissect.Parser(pattern, appendSeparator, parser), keys);
            } catch (DissectException e) {
                throw new ParsingException(src, "Invalid pattern for dissect: [{}]", pattern);
            }
        };
    }

    @Override
    public PlanFactory visitMvExpandCommand(EsqlBaseParser.MvExpandCommandContext ctx) {
        UnresolvedAttribute field = visitQualifiedName(ctx.qualifiedName());
        Source src = source(ctx);
        return child -> new MvExpand(src, child, field, new UnresolvedAttribute(src, field.name()));

    }

    @Override
    public Map<String, Object> visitCommandOptions(EsqlBaseParser.CommandOptionsContext ctx) {
        if (ctx == null) {
            return Map.of();
        }
        Map<String, Object> result = new HashMap<>();
        for (EsqlBaseParser.CommandOptionContext option : ctx.commandOption()) {
            result.put(visitIdentifier(option.identifier()), expression(option.constant()).fold());
        }
        return result;
    }

    @Override
    public LogicalPlan visitRowCommand(EsqlBaseParser.RowCommandContext ctx) {
        return new Row(source(ctx), visitFields(ctx.fields()));
    }

    @Override
    public LogicalPlan visitFromCommand(EsqlBaseParser.FromCommandContext ctx) {
        Source source = source(ctx);
        TableIdentifier table = new TableIdentifier(source, null, visitFromIdentifiers(ctx.fromIdentifier()));
        Map<String, Attribute> metadataMap = new LinkedHashMap<>();
        if (ctx.metadata() != null) {
            for (var c : ctx.metadata().fromIdentifier()) {
                String id = visitFromIdentifier(c);
                Source src = source(c);
                if (MetadataAttribute.isSupported(id) == false) {
                    throw new ParsingException(src, "unsupported metadata field [" + id + "]");
                }
                Attribute a = metadataMap.put(id, MetadataAttribute.create(src, id));
                if (a != null) {
                    throw new ParsingException(src, "metadata field [" + id + "] already declared [" + a.source().source() + "]");
                }
            }
        }
        return new EsqlUnresolvedRelation(source, table, Arrays.asList(metadataMap.values().toArray(Attribute[]::new)));
    }

    @Override
    public PlanFactory visitStatsCommand(EsqlBaseParser.StatsCommandContext ctx) {
        List<NamedExpression> aggregates = new ArrayList<>(visitFields(ctx.stats));
        List<NamedExpression> groupings = visitGrouping(ctx.grouping);
        if (aggregates.isEmpty() && groupings.isEmpty()) {
            throw new ParsingException(source(ctx), "At least one aggregation or grouping expression required in [{}]", ctx.getText());
        }
        // grouping keys are automatically added as aggregations however the user is not allowed to specify them
        if (groupings.isEmpty() == false && aggregates.isEmpty() == false) {
            var groupNames = new LinkedHashSet<>(Expressions.names(Expressions.references(groupings)));

            for (NamedExpression aggregate : aggregates) {
                if (aggregate instanceof Alias a && a.child() instanceof UnresolvedAttribute ua && groupNames.contains(ua.name())) {
                    throw new ParsingException(ua.source(), "Cannot specify grouping expression [{}] as an aggregate", ua.name());
                }
            }
        }
        aggregates.addAll(groupings);
        return input -> new Aggregate(source(ctx), input, new ArrayList<>(groupings), aggregates);
    }

    @Override
    public PlanFactory visitInlinestatsCommand(EsqlBaseParser.InlinestatsCommandContext ctx) {
        List<NamedExpression> aggregates = new ArrayList<>(visitFields(ctx.stats));
        List<NamedExpression> groupings = visitGrouping(ctx.grouping);
        aggregates.addAll(groupings);
        return input -> new InlineStats(source(ctx), input, new ArrayList<>(groupings), aggregates);
    }

    @Override
    public PlanFactory visitWhereCommand(EsqlBaseParser.WhereCommandContext ctx) {
        Expression expression = expression(ctx.booleanExpression());
        return input -> new Filter(source(ctx), input, expression);
    }

    @Override
    public PlanFactory visitLimitCommand(EsqlBaseParser.LimitCommandContext ctx) {
        Source source = source(ctx);
        int limit = Integer.parseInt(ctx.INTEGER_LITERAL().getText());
        return input -> new Limit(source, new Literal(source, limit, DataTypes.INTEGER), input);
    }

    @Override
    public PlanFactory visitSortCommand(EsqlBaseParser.SortCommandContext ctx) {
        List<Order> orders = visitList(this, ctx.orderExpression(), Order.class);
        Source source = source(ctx);
        return input -> new OrderBy(source, input, orders);
    }

    @Override
    public Explain visitExplainCommand(EsqlBaseParser.ExplainCommandContext ctx) {
        return new Explain(source(ctx), plan(ctx.subqueryExpression().query()));
    }

    @Override
    public PlanFactory visitDropCommand(EsqlBaseParser.DropCommandContext ctx) {
        var identifiers = ctx.qualifiedNamePattern();
        List<NamedExpression> removals = new ArrayList<>(identifiers.size());

        for (QualifiedNamePatternContext patternContext : identifiers) {
            NamedExpression ne = visitQualifiedNamePattern(patternContext);
            if (ne instanceof UnresolvedStar) {
                var src = ne.source();
                throw new ParsingException(src, "Removing all fields is not allowed [{}]", src.text());
            }
            removals.add(ne);
        }

        return child -> new Drop(source(ctx), child, removals);
    }

    @Override
    public PlanFactory visitRenameCommand(EsqlBaseParser.RenameCommandContext ctx) {
        List<Alias> renamings = ctx.renameClause().stream().map(this::visitRenameClause).toList();
        return child -> new Rename(source(ctx), child, renamings);
    }

    @Override
    public PlanFactory visitKeepCommand(EsqlBaseParser.KeepCommandContext ctx) {
        if (ctx.PROJECT() != null) {
            addWarning("PROJECT command is no longer supported, please use KEEP instead");
        }
        var identifiers = ctx.qualifiedNamePattern();
        List<NamedExpression> projections = new ArrayList<>(identifiers.size());
        boolean hasSeenStar = false;
        for (QualifiedNamePatternContext patternContext : identifiers) {
            NamedExpression ne = visitQualifiedNamePattern(patternContext);
            if (ne instanceof UnresolvedStar) {
                if (hasSeenStar) {
                    var src = ne.source();
                    throw new ParsingException(src, "Cannot specify [*] more than once", src.text());
                } else {
                    hasSeenStar = true;
                }
            }
            projections.add(ne);
        }
        return child -> new Keep(source(ctx), child, projections);
    }

    @Override
    public LogicalPlan visitShowInfo(EsqlBaseParser.ShowInfoContext ctx) {
        return new ShowInfo(source(ctx));
    }

    @Override
    public LogicalPlan visitShowFunctions(EsqlBaseParser.ShowFunctionsContext ctx) {
        return new ShowFunctions(source(ctx));
    }

    @Override
    public PlanFactory visitEnrichCommand(EsqlBaseParser.EnrichCommandContext ctx) {
        return p -> {
            String policyName = ctx.policyName.getText();
            var source = source(ctx);
            Mode mode = enrichMode(ctx.setting());

            NamedExpression matchField = ctx.ON() != null ? visitQualifiedNamePattern(ctx.matchField) : new EmptyAttribute(source);
            if (matchField.name().contains("*")) {
                throw new ParsingException(source, "Using wildcards (*) in ENRICH WITH projections is not allowed [{}]", matchField.name());
            }

            List<NamedExpression> keepClauses = visitList(this, ctx.enrichWithClause(), NamedExpression.class);
            return new Enrich(
                source,
                p,
                mode,
                new Literal(source(ctx.policyName), policyName, DataTypes.KEYWORD),
                matchField,
                null,
                Map.of(),
                keepClauses.isEmpty() ? List.of() : keepClauses
            );
        };
    }

    private Mode enrichMode(List<EsqlBaseParser.SettingContext> setting) {
        if (setting == null || setting.isEmpty()) {
            return null;
        }
        var s = setting.get(0);
        var source = source(s);
        if (setting.size() > 1) {
            throw new ParsingException(source, "Only one setting allowed for now in ENRICH");
        }
        String mode = "ccq.mode";

        var nameText = s.name.getText();
        if (mode.equals(nameText.toLowerCase(Locale.ROOT)) == false) {
            throw new ParsingException(source(s.name), "Unsupported setting [{}], expected [{}]", nameText, mode);
        }

        var valueText = s.value.getText();
        Enrich.Mode m = Enrich.Mode.from(valueText);
        if (m == null) {
            throw new ParsingException(
                source(s.value),
                "Unrecognized value [{}], ENRICH [{}] needs to be one of {}",
                valueText,
                nameText,
                Enrich.Mode.values()
            );
        }
        return m;
    }

    interface PlanFactory extends Function<LogicalPlan, LogicalPlan> {}
}
