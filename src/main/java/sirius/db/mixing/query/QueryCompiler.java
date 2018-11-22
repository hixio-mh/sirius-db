/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.db.mixing.query;

import parsii.tokenizer.LookaheadReader;
import sirius.db.mixing.EntityDescriptor;
import sirius.db.mixing.Mapping;
import sirius.db.mixing.Property;
import sirius.db.mixing.properties.BaseEntityRefProperty;
import sirius.db.mixing.query.constraints.Constraint;
import sirius.db.mixing.query.constraints.FilterFactory;
import sirius.kernel.commons.Strings;
import sirius.kernel.commons.Value;
import sirius.kernel.di.GlobalContext;
import sirius.kernel.di.std.Part;

import javax.annotation.Nullable;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an SQL like query and compiles it into a {@link Constraint}.
 * <p>
 * It also provides support for {@link QueryTag}s embedded in the given query.
 *
 * @param <C> the type of constraints generated by this compiler
 */
public abstract class QueryCompiler<C extends Constraint> {

    /**
     * Represents a value parsed for a field.
     */
    protected static class FieldValue {
        Object value;
        boolean exact;

        protected FieldValue(Object value, boolean exact) {
            this.value = value;
            this.exact = exact;
        }

        /**
         * Returns the value itself
         *
         * @return the parsed (and tranformed) value
         */
        public Object getValue() {
            return value;
        }

        /**
         * Determins if the value must not be processed any further.
         *
         * @return <tt>true</tt> if the value was explicitely given (e.g. in quotes), <tt>false</tt> otherwise
         */
        public boolean isExact() {
            return exact;
        }
    }

    protected FilterFactory<C> factory;
    protected EntityDescriptor descriptor;
    protected final List<QueryField> searchFields;
    protected final LookaheadReader reader;

    @Part
    protected static GlobalContext ctx;

    /**
     * Creates a new instance for the given factory entity and query.
     *
     * @param factory      the factory used to create constraints
     * @param descriptor   the descriptor of entities being queried
     * @param query        the query to compile
     * @param searchFields the default search fields to query
     */
    protected QueryCompiler(FilterFactory<C> factory,
                            EntityDescriptor descriptor,
                            String query,
                            List<QueryField> searchFields) {
        this.factory = factory;
        this.descriptor = descriptor;
        this.searchFields = searchFields;
        this.reader = new LookaheadReader(new StringReader(query));
    }

    private boolean skipWhitespace(LookaheadReader reader) {
        boolean skipped = false;
        while (reader.current().isWhitepace()) {
            reader.consume();
            skipped = true;
        }

        return skipped;
    }

    /**
     * Compiles the query into a constraint.
     *
     * @return the compiled constraint
     */
    @Nullable
    public C compile() {
        return parseOR();
    }

    private boolean isAtOR(LookaheadReader reader) {
        return reader.current().is('o', 'O') && reader.next().is('r', 'R');
    }

    private boolean isAtBinaryAND(LookaheadReader reader) {
        return reader.current().is('&') && reader.next().is('&');
    }

    private boolean isAtAND(LookaheadReader reader) {
        return reader.current().is('a', 'A') && reader.next().is('n', 'N') && reader.next(2).is('d', 'D');
    }

    private C parseOR() {
        List<C> constraints = new ArrayList<>();
        while (!reader.current().isEndOfInput() && !reader.current().is(')')) {
            C inner = parseAND();
            if (inner != null) {
                constraints.add(inner);
            }
            if (!isAtOR(reader)) {
                break;
            } else {
                reader.consume(2);
            }
        }

        if (constraints.isEmpty()) {
            return null;
        } else {
            return factory.or(constraints);
        }
    }

    private C parseAND() {
        List<C> constraints = new ArrayList<>();
        while (!reader.current().isEndOfInput() && !reader.current().is(')')) {
            C inner = parseExpression();
            if (inner != null) {
                constraints.add(inner);
            }
            skipWhitespace(reader);
            if (isAtOR(reader)) {
                break;
            }
            if (isAtAND(reader)) {
                reader.consume(3);
            }
            if (isAtBinaryAND(reader)) {
                reader.consume(2);
            }
        }

        if (constraints.isEmpty()) {
            return null;
        } else {
            return factory.and(constraints);
        }
    }

    private C parseExpression() {
        skipWhitespace(reader);

        if (reader.current().is('!')) {
            reader.consume();
            return factory.not(parseExpression());
        }

        if (reader.current().is('(')) {
            return parseBrackets();
        }

        if (reader.current().is('|') && reader.next().is('|')) {
            return parseTag();
        }

        while (!reader.current().isEndOfInput() && !continueToken(false)) {
            reader.consume();
        }

        if (reader.current().isEndOfInput()) {
            return null;
        }

        FieldValue token = readToken();
        boolean skipped = skipWhitespace(reader);
        if (isAtOperator()) {
            String field = token.getValue().toString();
            Property property = resolveProperty(field);

            return compileContraint(property, token, skipped);
        }

        return compileDefaultSearch(searchFields, token);
    }

    protected C compileContraint(Property property, FieldValue token, boolean skipped) {
        if (property != null) {
            return parseOperation(property, token.getValue().toString());
        }

        if (!skipped) {
            return compileDefaultSearch(searchFields, treatOperatorAsTokenPart(token));
        }

        return compileDefaultSearch(searchFields, token);
    }

    /**
     * Handles an operator which was found when reading a field token as part of a value, as the field isn't a property
     * (real database field) of the underlying entity).
     * <p>
     * An example would be something like <pre>hello:world</pre> where <b>hello</b> isn't an actual field of the
     * entity being searched. Therefore we have to emit a {@link #compileDefaultSearch(List, FieldValue)} with
     * <pre>hello:world</pre> as token.
     *
     * @param token the partial field/token
     * @return the enhanced field token.
     */
    private FieldValue treatOperatorAsTokenPart(FieldValue token) {
        StringBuilder additionalToken = new StringBuilder();
        while (isAtOperator() || continueToken(false)) {
            if (reader.current().is('\\')) {
                reader.consume();
            }
            additionalToken.append(reader.consume());
        }

        token = new FieldValue(token.getValue().toString() + additionalToken, token.isExact());
        return token;
    }

    protected C compileDefaultSearch(List<QueryField> searchFields, FieldValue token) {
        List<C> constraints = new ArrayList<>();
        if (token.isExact()) {
            List<C> fieldConstraints = new ArrayList<>();
            for (QueryField field : searchFields) {
                fieldConstraints.add(compileSearchToken(field.getField(),
                                                        QueryField.Mode.EQUAL,
                                                        token.getValue().toString()));
            }
            constraints.add(factory.or(fieldConstraints));
        } else {
            for (String word : token.getValue().toString().split("\\s")) {
                List<C> fieldConstraints = new ArrayList<>();
                for (QueryField field : searchFields) {
                    fieldConstraints.add(compileSearchToken(field.getField(), field.getMode(), word));
                }
                constraints.add(factory.or(fieldConstraints));
            }
        }
        return factory.and(constraints);
    }

    protected abstract C compileSearchToken(Mapping field, QueryField.Mode mode, String value);

    protected C parseOperation(Property property, String field) {
        String operation = readOp();

        FieldValue value = compileValue(property, parseValue());

        return compileOperation(Mapping.named(field), operation, value);
    }

    private C compileOperation(Mapping field, String operation, FieldValue value) {
        switch (operation) {
            case ">":
                return factory.gt(field, value.getValue());
            case ">=":
                return factory.gte(field, value.getValue());
            case "<=":
                return factory.lte(field, value.getValue());
            case "<":
                return factory.lt(field, value.getValue());
            case "<>":
                return factory.ne(field, value);
            default:
                return compileFieldEquals(field, value);
        }
    }

    protected C compileFieldEquals(Mapping field, FieldValue value) {
        return factory.eq(field, value.getValue());
    }

    private FieldValue parseValue() {
        skipWhitespace(reader);
        if (reader.current().is('"')) {
            reader.consume();
            StringBuilder result = new StringBuilder();
            while (!reader.current().isEndOfInput() && !reader.current().is('"')) {
                if (reader.current().is('\\')) {
                    reader.consume();
                }
                result.append(reader.consume());
            }
            reader.consume();
            return new FieldValue(result.toString(), true);
        } else {
            return new FieldValue(readValue().getValue(), false);
        }
    }

    private FieldValue readValue() {
        StringBuilder token = new StringBuilder();
        boolean inQuotes = reader.current().is('"');
        if (inQuotes) {
            reader.consume();
        }
        while (continueValue(inQuotes)) {
            if (reader.current().is('\\')) {
                reader.consume();
            }
            token.append(reader.consume());
        }
        if (inQuotes && reader.current().is('"')) {
            reader.consume();
        }
        return new FieldValue(token.toString(), inQuotes);
    }

    private boolean continueValue(boolean inQuotes) {
        if (reader.current().isEndOfInput()) {
            return false;
        }

        if (inQuotes) {
            return !reader.current().is('"');
        }

        return !reader.current().is(')') && !reader.current().isWhitepace();
    }

    protected FieldValue compileValue(Property property, FieldValue value) {
        if (!value.isExact() && "-".equals(value.getValue())) {
            return new FieldValue(null, false);
        }

        return new FieldValue(property.transformValue(Value.of(value.getValue())), value.isExact());
    }

    private Property resolveProperty(String property) {
        EntityDescriptor effectiveDescriptor = descriptor;
        String[] path = property.split("\\.");
        for (int i = 0; i < path.length - 2; i++) {
            Property reference = effectiveDescriptor.findProperty(path[i]);
            if (reference instanceof BaseEntityRefProperty) {
                effectiveDescriptor = ((BaseEntityRefProperty<?, ?, ?>) reference).getReferencedDescriptor();
            } else {
                return null;
            }
        }

        return effectiveDescriptor.findProperty(path[path.length - 1]);
    }

    private String readOp() {
        if (isNotEqual()) {
            reader.consume(2);
            return "<>";
        }
        if (reader.current().is('<') && reader.next().is('=')) {
            reader.consume(2);
            return "<=";
        }
        if (reader.current().is('>') && reader.next().is('=')) {
            reader.consume(2);
            return ">=";
        }
        if (reader.current().is('=') || reader.current().is(':')) {
            reader.consume();
            return "=";
        }
        if (reader.current().is('>')) {
            reader.consume();
            return ">";
        }
        if (reader.current().is('<')) {
            reader.consume();
            return "<";
        } else {
            throw new IllegalStateException(reader.current().toString());
        }
    }

    private boolean isNotEqual() {
        if (reader.current().is('!') && reader.next().is('=')) {
            return true;
        }

        return reader.current().is('<') && reader.next().is('>');
    }

    private FieldValue readToken() {
        StringBuilder token = new StringBuilder();
        boolean inQuotes = reader.current().is('"');
        if (inQuotes) {
            reader.consume();
        }
        while (continueToken(inQuotes)) {
            if (reader.current().is('\\')) {
                reader.consume();
            }
            token.append(reader.consume());
        }
        if (inQuotes && reader.current().is('"')) {
            reader.consume();
        }
        return new FieldValue(token.toString(), inQuotes);
    }

    private boolean continueToken(boolean inQuotes) {
        if (reader.current().isEndOfInput()) {
            return false;
        }

        if (inQuotes) {
            return !reader.current().is('"');
        }

        return !reader.current().is(')', ':') && !reader.current().isWhitepace() && !isAtOperator();
    }

    @SuppressWarnings("unchecked")
    private C parseTag() {
        StringBuilder tag = new StringBuilder();
        tag.append(reader.consume());
        tag.append(reader.consume());
        while (!reader.current().isEndOfInput() && !(reader.current().is('|') && reader.next().is('|'))) {
            tag.append(reader.consume());
        }
        tag.append(reader.consume());
        tag.append(reader.consume());

        QueryTag queryTag = QueryTag.parse(tag.toString());
        if (queryTag.getType() != null && Strings.isFilled(queryTag.getValue())) {
            QueryTagHandler<C> handler = ctx.getPart(queryTag.getType(), QueryTagHandler.class);
            if (handler != null) {
                return handler.generateConstraint(factory, descriptor, queryTag.getValue());
            }
        }

        return null;
    }

    private C parseBrackets() {
        reader.consume();
        C inner = parseOR();
        if (reader.current().is(')')) {
            reader.consume();
        }

        return inner;
    }

    private boolean isAtOperator() {
        if (reader.current().is('=', ':')) {
            return true;
        }

        if (reader.current().is('!') && reader.next().is('=')) {
            return true;
        }

        return reader.current().is('<', '>');
    }
}
