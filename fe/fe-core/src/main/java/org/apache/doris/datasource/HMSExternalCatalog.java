// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.datasource;

import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.HiveMetaStoreClientHelper;
import org.apache.doris.catalog.external.ExternalDatabase;
import org.apache.doris.catalog.external.HMSExternalDatabase;
import org.apache.doris.cluster.ClusterNamespace;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.util.Util;
import org.apache.doris.qe.MasterCatalogExecutor;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.TException;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * External catalog for hive metastore compatible data sources.
 */
public class HMSExternalCatalog extends ExternalCatalog {
    private static final Logger LOG = LogManager.getLogger(HMSExternalCatalog.class);

    protected IMetaStoreClient client;

    /**
     * Default constructor for HMSExternalCatalog.
     */
    public HMSExternalCatalog(long catalogId, String name, Map<String, String> props) {
        this.id = catalogId;
        this.name = name;
        this.type = "hms";
        this.catalogProperty = new CatalogProperty();
        this.catalogProperty.setProperties(props);
    }

    public String getHiveMetastoreUris() {
        return catalogProperty.getOrDefault("hive.metastore.uris", "");
    }

    private void init() {
        Map<String, Long> tmpDbNameToId = Maps.newConcurrentMap();
        Map<Long, ExternalDatabase> tmpIdToDb = Maps.newConcurrentMap();
        InitCatalogLog initCatalogLog = new InitCatalogLog();
        initCatalogLog.setCatalogId(id);
        initCatalogLog.setType(InitCatalogLog.Type.HMS);
        List<String> allDatabases;
        try {
            allDatabases = client.getAllDatabases();
        } catch (TException e) {
            LOG.warn("Fail to init db name to id map. {}", e.getMessage());
            return;
        }
        // Update the db name to id map.
        if (allDatabases == null) {
            return;
        }
        for (String dbName : allDatabases) {
            long dbId;
            if (dbNameToId != null && dbNameToId.containsKey(dbName)) {
                dbId = dbNameToId.get(dbName);
                tmpDbNameToId.put(dbName, dbId);
                ExternalDatabase db = idToDb.get(dbId);
                db.setUnInitialized();
                tmpIdToDb.put(dbId, db);
                initCatalogLog.addRefreshDb(dbId);
            } else {
                dbId = Env.getCurrentEnv().getNextId();
                tmpDbNameToId.put(dbName, dbId);
                HMSExternalDatabase db = new HMSExternalDatabase(this, dbId, dbName);
                tmpIdToDb.put(dbId, db);
                initCatalogLog.addCreateDb(dbId, dbName);
            }
        }
        dbNameToId = tmpDbNameToId;
        idToDb = tmpIdToDb;
        initialized = true;
        Env.getCurrentEnv().getEditLog().logInitCatalog(initCatalogLog);
    }

    /**
     * Catalog can't be init when creating because the external catalog may depend on third system.
     * So you have to make sure the client of third system is initialized before any method was called.
     */
    @Override
    public synchronized void makeSureInitialized() {
        if (!objectCreated) {
            try {
                HiveConf hiveConf = new HiveConf();
                hiveConf.setVar(HiveConf.ConfVars.METASTOREURIS, getHiveMetastoreUris());
                client = HiveMetaStoreClientHelper.getClient(hiveConf);
                objectCreated = true;
            } catch (DdlException e) {
                Util.logAndThrowRuntimeException(LOG,
                        String.format("failed to create hive meta store client for catalog: %s", name), e);
            }
        }
        if (!initialized) {
            if (!Env.getCurrentEnv().isMaster()) {
                // Forward to master and wait the journal to replay.
                MasterCatalogExecutor remoteExecutor = new MasterCatalogExecutor();
                try {
                    remoteExecutor.forward(id, -1, -1);
                } catch (Exception e) {
                    Util.logAndThrowRuntimeException(LOG,
                            String.format("failed to forward init catalog %s operation to master.", name), e);
                }
                return;
            }
            init();
        }
    }

    @Override
    public List<String> listDatabaseNames(SessionContext ctx) {
        makeSureInitialized();
        return Lists.newArrayList(dbNameToId.keySet());
    }

    @Override
    public List<String> listTableNames(SessionContext ctx, String dbName) {
        makeSureInitialized();
        HMSExternalDatabase hmsExternalDatabase = (HMSExternalDatabase) idToDb.get(dbNameToId.get(dbName));
        if (hmsExternalDatabase != null && hmsExternalDatabase.isInitialized()) {
            List<String> names = Lists.newArrayList();
            hmsExternalDatabase.getTables().stream().forEach(table -> names.add(table.getName()));
            return names;
        } else {
            try {
                return client.getAllTables(getRealTableName(dbName));
            } catch (TException e) {
                Util.logAndThrowRuntimeException(LOG, String.format("list table names failed for %s.%s", name, dbName),
                        e);
            }
        }
        return Lists.newArrayList();
    }

    @Override
    public boolean tableExist(SessionContext ctx, String dbName, String tblName) {
        try {
            return client.tableExists(getRealTableName(dbName), tblName);
        } catch (TException e) {
            Util.logAndThrowRuntimeException(LOG,
                    String.format("check table exist failed for %s.%s.%s", name, dbName, tblName), e);
        }
        return false;
    }

    @Nullable
    @Override
    public ExternalDatabase getDbNullable(String dbName) {
        makeSureInitialized();
        String realDbName = ClusterNamespace.getNameFromFullName(dbName);
        if (!dbNameToId.containsKey(realDbName)) {
            return null;
        }
        return idToDb.get(dbNameToId.get(realDbName));
    }

    @Nullable
    @Override
    public ExternalDatabase getDbNullable(long dbId) {
        makeSureInitialized();
        return idToDb.get(dbId);
    }

    @Override
    public List<Long> getDbIds() {
        makeSureInitialized();
        return Lists.newArrayList(dbNameToId.values());
    }

    public ExternalDatabase getDbForReplay(long dbId) {
        return idToDb.get(dbId);
    }

    public IMetaStoreClient getClient() {
        makeSureInitialized();
        return client;
    }
}
