/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.postgresql;

import io.r2dbc.postgresql.api.PostgresqlStatement;
import io.r2dbc.postgresql.client.Binding;
import io.r2dbc.postgresql.client.Client;
import io.r2dbc.postgresql.client.ExtendedQueryMessageFlow;
import io.r2dbc.postgresql.client.PortalNameSupplier;
import io.r2dbc.postgresql.codec.Codecs;
import io.r2dbc.postgresql.message.backend.BackendMessage;
import io.r2dbc.postgresql.message.backend.BindComplete;
import io.r2dbc.postgresql.message.backend.CloseComplete;
import io.r2dbc.postgresql.message.backend.NoData;
import io.r2dbc.postgresql.util.Assert;
import io.r2dbc.postgresql.util.GeneratedValuesUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;

import static io.r2dbc.postgresql.client.ExtendedQueryMessageFlow.PARAMETER_SYMBOL;
import static io.r2dbc.postgresql.util.PredicateUtils.not;
import static io.r2dbc.postgresql.util.PredicateUtils.or;

final class ExtendedQueryPostgresqlStatement implements PostgresqlStatement {

    private static final Predicate<BackendMessage> RESULT_FRAME_FILTER = not(or(BindComplete.class::isInstance, NoData.class::isInstance));

    private final Bindings bindings;

    private final Client client;

    private final Codecs codecs;

    private final boolean forceBinary;

    private final PortalNameSupplier portalNameSupplier;

    private final String sql;

    private final StatementCache statementCache;

    private String[] generatedColumns;

    ExtendedQueryPostgresqlStatement(Client client, Codecs codecs, PortalNameSupplier portalNameSupplier, String sql, StatementCache statementCache, boolean forceBinary) {
        this.client = Assert.requireNonNull(client, "client must not be null");
        this.codecs = Assert.requireNonNull(codecs, "codecs must not be null");
        this.portalNameSupplier = Assert.requireNonNull(portalNameSupplier, "portalNameSupplier must not be null");
        this.sql = Assert.requireNonNull(sql, "sql must not be null");
        this.statementCache = Assert.requireNonNull(statementCache, "statementCache must not be null");
        this.forceBinary = forceBinary;
        this.bindings = new Bindings(expectedSize(sql));
    }

    @Override
    public ExtendedQueryPostgresqlStatement add() {
        this.bindings.finish();
        return this;
    }

    @Override
    public ExtendedQueryPostgresqlStatement bind(String identifier, Object value) {
        Assert.requireNonNull(identifier, "identifier must not be null");
        Assert.requireType(identifier, String.class, "identifier must be a String");

        return bind(getIndex(identifier), value);
    }

    @Override
    public ExtendedQueryPostgresqlStatement bind(int index, Object value) {
        Assert.requireNonNull(value, "value must not be null");

        this.bindings.getCurrent().add(index, this.codecs.encode(value));

        return this;
    }

    @Override
    public ExtendedQueryPostgresqlStatement bindNull(String identifier, Class<?> type) {
        Assert.requireNonNull(identifier, "identifier must not be null");
        Assert.requireType(identifier, String.class, "identifier must be a String");
        Assert.requireNonNull(type, "type must not be null");

        bindNull(getIndex(identifier), type);
        return this;
    }

    @Override
    public ExtendedQueryPostgresqlStatement bindNull(int index, Class<?> type) {
        Assert.requireNonNull(type, "type must not be null");

        this.bindings.getCurrent().add(index, this.codecs.encodeNull(type));
        return this;
    }

    @Override
    public Flux<io.r2dbc.postgresql.api.PostgresqlResult> execute() {
        if (this.generatedColumns == null) {
            return execute(this.sql);
        }

        return execute(GeneratedValuesUtils.augment(this.sql, this.generatedColumns));
    }

    @Override
    public ExtendedQueryPostgresqlStatement returnGeneratedValues(String... columns) {
        Assert.requireNonNull(columns, "columns must not be null");

        if (GeneratedValuesUtils.hasReturningClause(this.sql)) {
            throw new IllegalStateException("Statement already includes RETURNING clause");
        }

        if (!GeneratedValuesUtils.isSupportedCommand(this.sql)) {
            throw new IllegalStateException("Statement is not a DELETE, INSERT, or UPDATE command");
        }

        this.generatedColumns = columns;
        return this;
    }

    @Override
    public String toString() {
        return "ExtendedQueryPostgresqlStatement{" +
            "bindings=" + this.bindings +
            ", client=" + this.client +
            ", codecs=" + this.codecs +
            ", forceBinary=" + this.forceBinary +
            ", portalNameSupplier=" + this.portalNameSupplier +
            ", sql='" + this.sql + '\'' +
            ", statementCache=" + this.statementCache +
            ", generatedColumns=" + Arrays.toString(this.generatedColumns) +
            '}';
    }

    static boolean supports(String sql) {
        Assert.requireNonNull(sql, "sql must not be null");

        return !sql.trim().isEmpty() && !sql.contains(";") && sql.contains("$1");
    }

    Binding getCurrentBinding() {
        return this.bindings.getCurrent();
    }

    private static int expectedSize(String sql) {
        Matcher m = PARAMETER_SYMBOL.matcher(sql);
        Set<String> paramNames = new HashSet<>();

        int count = 0;
        while (m.find()) {
            if (paramNames.add(m.group())) {
                count++;
            }
        }

        return count;
    }

    private Flux<io.r2dbc.postgresql.api.PostgresqlResult> execute(String sql) {
        this.bindings.finish();

        ExceptionFactory factory = ExceptionFactory.withSql(sql);
        return this.statementCache.getName(this.bindings.first(), sql)
            .flatMapMany(name -> ExtendedQueryMessageFlow
                .execute(this.bindings.bindings, this.client, this.portalNameSupplier, name, sql, this.forceBinary))
            .filter(RESULT_FRAME_FILTER)
            .windowUntil(CloseComplete.class::isInstance)
            .map(messages -> PostgresqlResult.toResult(this.codecs, messages, factory));
    }

    private int getIndex(String identifier) {
        Matcher matcher = PARAMETER_SYMBOL.matcher(identifier);

        if (!matcher.find()) {
            throw new IllegalArgumentException(String.format("Identifier '%s' is not a valid identifier. Should be of the pattern '%s'.", identifier, PARAMETER_SYMBOL.pattern()));
        }

        return Integer.parseInt(matcher.group(1)) - 1;
    }

    private static final class Bindings {

        private final List<Binding> bindings = new ArrayList<>();

        private final int expectedSize;

        private Binding current;

        private Bindings(int expectedSize) {
            this.expectedSize = expectedSize;
        }

        @Override
        public String toString() {
            return "Bindings{" +
                "bindings=" + this.bindings +
                ", current=" + this.current +
                '}';
        }

        private void finish() {
            if (this.current != null) {
                this.current.validate();
            }

            this.current = null;
        }

        private Binding first() {
            if (this.bindings.isEmpty()) {
                throw new IllegalStateException("No parameters have been bound");
            }
            return this.bindings.get(0);
        }

        private Binding getCurrent() {
            if (this.current == null) {
                this.current = new Binding(this.expectedSize);
                this.bindings.add(this.current);
            }

            return this.current;
        }

    }

}
