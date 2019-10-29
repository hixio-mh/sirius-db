/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.db.jdbc.batch;

import sirius.db.jdbc.OMA;
import sirius.db.jdbc.SQLEntity;
import sirius.db.mixing.Mapping;
import sirius.kernel.async.Operation;
import sirius.kernel.di.std.Part;
import sirius.kernel.health.Exceptions;
import sirius.kernel.health.HandledException;

import javax.annotation.concurrent.NotThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Bundles the preparation and execution of a bunch of {@link BatchQuery batch queries}.
 * <p>
 * Note that this context can only operate on databases managed via {@link sirius.db.mixing.Mixing}. To
 * perform batch operations against external JDBC databases use {@link sirius.db.jdbc.batch.external.ExternalBatchContext}.
 */
@NotThreadSafe
public class BatchContext implements Closeable {

    @Part
    private static OMA oma;

    private List<BatchQuery<?>> queries = new ArrayList<>();
    private Map<String, Connection> connectionsPerRealm = new HashMap<>();
    private Operation op;

    /**
     * Creates a new context with the given debugging description and the expected runtime.
     *
     * @param description      a provider for a description used for debugging purposes
     * @param expectedDuration the expected duration of the whole batch operation
     */
    public BatchContext(Supplier<String> description, Duration expectedDuration) {
        this.op = new Operation(description, expectedDuration);
    }

    private <Q extends BatchQuery<?>> Q register(Q query) {
        if (queries == null) {
            reportIllegalState();
        }
        this.queries.add(query);

        return query;
    }

    private void reportIllegalState() {
        throw new IllegalStateException("This batch context has already been closed.");
    }

    protected void unregister(BatchQuery<?> query) {
        if (queries == null) {
            reportIllegalState();
        }
        this.queries.remove(query);
    }

    protected static String[] simplifyMappings(Mapping[] mappingsToCompare) {
        return Arrays.stream(mappingsToCompare).map(Mapping::toString).toArray(n -> new String[n]);
    }

    protected Connection getConnection(String realm) {
        if (connectionsPerRealm == null) {
            reportIllegalState();
        }

        return connectionsPerRealm.computeIfAbsent(realm, this::createConnection);
    }

    protected Connection createConnection(String realm) {
        try {
            Connection connection = oma.getDatabase(realm).getConnection();
            changeAutoCommit(connection, false);
            return connection;
        } catch (SQLException e) {
            throw Exceptions.handle()
                            .to(OMA.LOG)
                            .error(e)
                            .withSystemErrorMessage("Failed to open a database connection for realm '%s': %s (%s)",
                                                    realm)
                            .handle();
        }
    }

    protected void safeClose() {
        if (queries == null) {
            return;
        }

        for (BatchQuery<?> query : queries) {
            try {
                query.tryCommit(false);
            } catch (HandledException e) {
                Exceptions.ignore(e);
            } catch (Exception e) {
                Exceptions.handle(OMA.LOG, e);
            }

            query.safeClose();
        }
        queries = null;

        connectionsPerRealm.values().forEach(this::safeCloseConnection);
        connectionsPerRealm = null;
    }

    private void safeCloseConnection(Connection connection) {
        try {
            changeAutoCommit(connection, true);
            connection.close();
        } catch (SQLException e) {
            Exceptions.handle()
                      .to(OMA.LOG)
                      .error(e)
                      .withSystemErrorMessage("An exception occured while closing a database connection: %s (%s)")
                      .handle();
        }
    }

    private void changeAutoCommit(Connection connection, boolean enable) {
        try {
            connection.setAutoCommit(enable);
        } catch (SQLException e) {
            Exceptions.handle()
                      .error(e)
                      .withSystemErrorMessage("An error occurred while changing the auto-commit of %s to %s - %s (%s)",
                                              connection,
                                              enable)
                      .handle();
        }
    }

    /**
     * Creates a {@link FindQuery find query}.
     *
     * @param type              the type of entities to find
     * @param mappingsToCompare the mappings to compare in order to find an entity
     * @param <E>               the generic type of the entities to find
     * @return the query used to find entities
     */
    public <E extends SQLEntity> FindQuery<E> findQuery(Class<E> type, Mapping... mappingsToCompare) {
        return register(new FindQuery<>(this, type, simplifyMappings(mappingsToCompare)));
    }

    /**
     * Returns an autoinitializing find query.
     * <p>
     * Based on the first call passed to {@link FindQuery#find(SQLEntity)} the type of entities to
     * resolve is determined.
     * <p>
     * This is used in JavaScript as there is no real notion of class literals. Also it is quite simpler to provide
     * mappings as string rather than as real {@link Mapping}.
     *
     * @param mappingsToCompare the mappings to compare in order to find an entity
     * @return the query used to find entities
     */
    public FindQuery<?> autoFindQuery(String... mappingsToCompare) {
        return register(new FindQuery<>(this, null, mappingsToCompare));
    }

    /**
     * Creates a new {@link InsertQuery insert query}.
     *
     * @param type             the type of entities to insert
     * @param fetchId          <tt>true</tt> if generated id should be fetched, <tt>false otherwise</tt>
     * @param mappingsToInsert the fields or mappings to insert
     * @param <E>              the generic type of the entities to insert
     * @return the query used to insert entities into the database
     */
    public <E extends SQLEntity> InsertQuery<E> insertQuery(Class<E> type,
                                                            boolean fetchId,
                                                            Mapping... mappingsToInsert) {
        return register(new InsertQuery<>(this, type, fetchId, simplifyMappings(mappingsToInsert)));
    }

    /**
     * Returns an autoinitializing insert query.
     *
     * @param fetchId          <tt>true</tt> if generated id should be fetched, <tt>false otherwise</tt>
     * @param mappingsToInsert the fields or mappings to insert
     * @return the query used to find entities
     * @see #autoFindQuery(String...)
     */
    public InsertQuery<?> autoInsertQuery(boolean fetchId, String... mappingsToInsert) {
        return register(new InsertQuery<>(this, null, fetchId, mappingsToInsert));
    }

    /**
     * Creates a new {@link InsertQuery insert query}.
     *
     * @param type             the type of entities to insert
     * @param mappingsToInsert the fields or mappings to insert
     * @param <E>              the generic type of the entities to insert
     * @return the query used to insert entities into the database
     */
    public <E extends SQLEntity> InsertQuery<E> insertQuery(Class<E> type, Mapping... mappingsToInsert) {
        return insertQuery(type, true, mappingsToInsert);
    }

    /**
     * Returns an autoinitializing insert query.
     *
     * @param mappingsToInsert the fields or mappings to insert
     * @return the query used to find entities
     * @see #autoFindQuery(String...)
     */
    public InsertQuery<?> autoInsertQuery(String... mappingsToInsert) {
        return autoInsertQuery(true, mappingsToInsert);
    }

    /**
     * Creates a new {@link UpdateQuery update query}.
     *
     * @param type              the type of entities to update
     * @param mappingsToCompare the mappings to compare in order to find the entity to update
     * @param <E>               the generic type of the entities to update
     * @return the query used to update entities in the database
     */
    public <E extends SQLEntity> UpdateQuery<E> updateQuery(Class<E> type, Mapping... mappingsToCompare) {
        return register(new UpdateQuery<>(this, type, simplifyMappings(mappingsToCompare)));
    }

    /**
     * Returns an autoinitializing update query.
     *
     * @param mappingsToCompare the mappings to compare in order to find the entity to update
     * @return the query used to update entities in the database
     * @see #autoFindQuery(String...)
     */
    public UpdateQuery<?> autoUpdateQuery(String... mappingsToCompare) {
        return register(new UpdateQuery<>(this, null, mappingsToCompare));
    }

    /**
     * Creates a new {@link UpdateQuery update query} which uses {@link SQLEntity#ID} as mapping to compare.
     *
     * @param type             the type of entities to insert
     * @param mappingsToUpdate the mappings to update
     * @param <E>              the generic type of the entities to update
     * @return the query used to update entities in the database
     */
    public <E extends SQLEntity> UpdateQuery<E> updateByIdQuery(Class<E> type, Mapping... mappingsToUpdate) {
        return register(new UpdateQuery<>(this, type, new String[]{SQLEntity.ID.getName()})).withUpdatedMappings(
                mappingsToUpdate);
    }

    /**
     * Returns an autoinitializing update query which uses {@link SQLEntity#ID} as mapping to compare.
     *
     * @param mappingsToUpdate the mappings to update
     * @return the query used to update entities in the database
     * @see #autoFindQuery(String...)
     */
    public UpdateQuery<?> autoUpdateByIdQuery(String... mappingsToUpdate) {
        return register(new UpdateQuery<>(this, null, new String[]{SQLEntity.ID.getName()}).withUpdatedMappings(
                mappingsToUpdate));
    }

    /**
     * Creates a new {@link DeleteQuery delete query}.
     *
     * @param type              the type of entities to delete
     * @param mappingsToCompare the mappings to compare in order to find the entity to delete
     * @param <E>               the generic type of the entities to delete
     * @return the query used to delete entities in the database
     */
    public <E extends SQLEntity> DeleteQuery<E> deleteQuery(Class<E> type, Mapping... mappingsToCompare) {
        return register(new DeleteQuery<>(this, type, simplifyMappings(mappingsToCompare)));
    }

    /**
     * Returns an autoinitializing delete query.
     *
     * @param mappingsToCompare the mappings to compare in order to find the entity to delete
     * @return the query used to delete entities in the database
     * @see #autoFindQuery(String...)
     */
    public DeleteQuery<?> autoDeleteQuery(String... mappingsToCompare) {
        return register(new DeleteQuery<>(this, null, mappingsToCompare));
    }

    /**
     * Prepares the given SQL statement as {@link CustomQuery custom query}.
     *
     * @param type    the type of entities to process
     * @param fetchId determines if generated IDs should be fetched
     * @param sql     the statement to prepare
     * @return the prepared statement wrapped as custom query
     */
    public CustomQuery customQuery(Class<? extends SQLEntity> type, boolean fetchId, String sql) {
        return register(new CustomQuery(this, type, fetchId, sql));
    }

    /**
     * Invokes {@link BatchQuery#tryCommit(boolean)} for all open queries.
     * <p>
     * Note that in most cases the automatic commit control of the batch context takes good control over when to
     * commit which query. This is mostly useful for edge cases or tests.
     */
    public void tryCommit() {
        if (queries == null) {
            return;
        }

        for (BatchQuery<?> query : queries) {
            try {
                query.tryCommit(false);
            } catch (Exception e) {
                throw Exceptions.handle()
                                .to(OMA.LOG)
                                .error(e)
                                .withSystemErrorMessage("An error occurred when flushing the BatchContext: %s (%s)")
                                .handle();
            }
        }
    }

    @Override
    public void close() throws IOException {
        safeClose();
        op.close();
    }

    /**
     * Determines if the batch context is empty.
     *
     * @return <tt>true</tt> if there are no queries registered at all, <tt>false</tt> otherwise
     */
    public boolean isEmpty() {
        return this.queries.stream().noneMatch(BatchQuery::isQueryAvailable);
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "Empty batch context";
        }

        StringBuilder sb = new StringBuilder("Batch Context\n");
        sb.append("----------------------------------------\n");
        for (BatchQuery<?> query : queries) {
            if (query.isQueryAvailable()) {
                sb.append(query).append("\n");
            }
        }
        sb.append("----------------------------------------\n");

        return sb.toString();
    }
}
