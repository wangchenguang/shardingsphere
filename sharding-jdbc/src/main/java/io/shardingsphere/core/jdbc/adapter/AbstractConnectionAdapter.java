/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
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
 * </p>
 */

package io.shardingsphere.core.jdbc.adapter;

import com.google.common.base.Preconditions;
import io.shardingsphere.core.constant.transaction.TransactionOperationType;
import io.shardingsphere.core.event.ShardingEventBusInstance;
import io.shardingsphere.core.event.transaction.xa.XATransactionEvent;
import io.shardingsphere.core.hint.HintManagerHolder;
import io.shardingsphere.core.jdbc.unsupported.AbstractUnsupportedOperationConnection;
import io.shardingsphere.core.routing.router.masterslave.MasterVisitedManager;
import io.shardingsphere.core.transaction.TransactionTypeHolder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * Adapter for {@code Connection}.
 *
 * @author zhangliang
 */
public abstract class AbstractConnectionAdapter extends AbstractUnsupportedOperationConnection {
    
    private final Map<String, Connection> cachedConnections = new HashMap<>();
    
    private boolean autoCommit = true;
    
    private boolean readOnly = true;
    
    private boolean closed;
    
    private int transactionIsolation = TRANSACTION_READ_UNCOMMITTED;
    
    /**
     * Get database connection.
     *
     * @param dataSourceName data source name
     * @return database connection
     * @throws SQLException SQL exception
     */
    public final Connection getConnection(final String dataSourceName) throws SQLException {
        if (cachedConnections.containsKey(dataSourceName)) {
            return cachedConnections.get(dataSourceName);
        }
        DataSource dataSource = getDataSourceMap().get(dataSourceName);
        Preconditions.checkState(null != dataSource, "Missing the data source name: '%s'", dataSourceName);
        Connection result = dataSource.getConnection();
        cachedConnections.put(dataSourceName, result);
        replayMethodsInvocation(result);
        return result;
    }
    
    protected abstract Map<String, DataSource> getDataSourceMap();
    
    protected final void removeCache(final Connection connection) {
        cachedConnections.values().remove(connection);
    }
    
    @Override
    public final boolean getAutoCommit() {
        return autoCommit;
    }
    
    @Override
    public final void setAutoCommit(final boolean autoCommit) throws SQLException {
        this.autoCommit = autoCommit;
        recordMethodInvocation(Connection.class, "setAutoCommit", new Class[] {boolean.class}, new Object[] {autoCommit});
        doTransaction(TransactionOperationType.BEGIN);
    }
    
    @Override
    public final void commit() throws SQLException {
        doTransaction(TransactionOperationType.COMMIT);
    }
    
    @Override
    public final void rollback() throws SQLException {
        doTransaction(TransactionOperationType.ROLLBACK);
    }
    
    private void doTransaction(final TransactionOperationType operationType) throws SQLException {
        switch (TransactionTypeHolder.get()) {
            case LOCAL:
                doLocalTransaction(operationType);
                break;
            case XA:
                ShardingEventBusInstance.getInstance().post(new XATransactionEvent(operationType));
                break;
            case BASE:
            default:
                throw new UnsupportedOperationException(TransactionTypeHolder.get().name());
        }
    }
    
    private void doLocalTransaction(final TransactionOperationType operationType) throws SQLException {
        Collection<SQLException> exceptions = new LinkedList<>();
        for (Connection each : cachedConnections.values()) {
            try {
                switch (operationType) {
                    case BEGIN:
                        each.setAutoCommit(autoCommit);
                        return;
                    case COMMIT:
                        each.commit();
                        return;
                    case ROLLBACK:
                        each.rollback();
                        return;
                    default:
                        throw new UnsupportedOperationException(operationType.name());
                }
            } catch (final SQLException ex) {
                exceptions.add(ex);
            }
            throwSQLExceptionIfNecessary(exceptions);
        }
    }
    
    @Override
    public final void close() throws SQLException {
        closed = true;
        HintManagerHolder.clear();
        MasterVisitedManager.clear();
        TransactionTypeHolder.clear();
        Collection<SQLException> exceptions = new LinkedList<>();
        for (Connection each : cachedConnections.values()) {
            try {
                each.close();
            } catch (final SQLException ex) {
                exceptions.add(ex);
            }
        }
        throwSQLExceptionIfNecessary(exceptions);
    }
    
    @Override
    public final boolean isClosed() {
        return closed;
    }
    
    @Override
    public final boolean isReadOnly() {
        return readOnly;
    }
    
    @Override
    public final void setReadOnly(final boolean readOnly) throws SQLException {
        this.readOnly = readOnly;
        recordMethodInvocation(Connection.class, "setReadOnly", new Class[] {boolean.class}, new Object[] {readOnly});
        for (Connection each : cachedConnections.values()) {
            each.setReadOnly(readOnly);
        }
    }
    
    @Override
    public final int getTransactionIsolation() throws SQLException {
        if (cachedConnections.values().isEmpty()) {
            return transactionIsolation;
        }
        return cachedConnections.values().iterator().next().getTransactionIsolation();
    }
    
    @Override
    public final void setTransactionIsolation(final int level) throws SQLException {
        transactionIsolation = level;
        recordMethodInvocation(Connection.class, "setTransactionIsolation", new Class[] {int.class}, new Object[] {level});
        for (Connection each : cachedConnections.values()) {
            each.setTransactionIsolation(level);
        }
    }
    
    // ------- Consist with MySQL driver implementation -------
    
    @Override
    public final SQLWarning getWarnings() {
        return null;
    }
    
    @Override
    public void clearWarnings() {
    }
    
    @Override
    public final int getHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }
    
    @Override
    public final void setHoldability(final int holdability) {
    }
}
