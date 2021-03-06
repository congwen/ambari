/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.upgrade;

import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.orm.DBAccessor.DBColumnInfo;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.SecurityType;
import org.apache.commons.lang.StringUtils;

import com.google.inject.Inject;
import com.google.inject.Injector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link UpgradeCatalog251} upgrades Ambari from 2.5.0 to 2.5.1.
 */
public class UpgradeCatalog251 extends AbstractUpgradeCatalog {

  static final String HOST_ROLE_COMMAND_TABLE = "host_role_command";
  static final String HRC_IS_BACKGROUND_COLUMN = "is_background";

  protected static final String KAFKA_BROKER_CONFIG = "kafka-broker";

  private static final String STAGE_TABLE = "stage";
  private static final String REQUEST_TABLE = "request";
  private static final String CLUSTER_HOST_INFO_COLUMN = "cluster_host_info";
  private static final String REQUEST_ID_COLUMN = "request_id";


  /**
   * Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(UpgradeCatalog251.class);

  /**
   * Constructor.
   *
   * @param injector
   */
  @Inject
  public UpgradeCatalog251(Injector injector) {
    super(injector);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSourceVersion() {
    return "2.5.0";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getTargetVersion() {
    return "2.5.1";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executeDDLUpdates() throws AmbariException, SQLException {
    addBackgroundColumnToHostRoleCommand();
    moveClusterHostColumnFromStageToRequest();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executePreDMLUpdates() throws AmbariException, SQLException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executeDMLUpdates() throws AmbariException, SQLException {
    addNewConfigurationsFromXml();
    updateKAFKAConfigs();
  }

  /**
   * Ensure that the updates from Ambari 2.4.0 are applied in the event the initial version is
   * Ambari 2.5.0, since this Kafka change failed to make it into Ambari 2.5.0.
   *
   * If the base version was before Ambari 2.5.0, this method should wind up doing nothing.
   * @throws AmbariException
   */
  protected void updateKAFKAConfigs() throws AmbariException {
    AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
    Clusters clusters = ambariManagementController.getClusters();
    if (clusters != null) {
      Map<String, Cluster> clusterMap = getCheckedClusterMap(clusters);
      if (clusterMap != null && !clusterMap.isEmpty()) {
        for (final Cluster cluster : clusterMap.values()) {
          Set<String> installedServices = cluster.getServices().keySet();

          if (installedServices.contains("KAFKA") && cluster.getSecurityType() == SecurityType.KERBEROS) {
            Config kafkaBroker = cluster.getDesiredConfigByType(KAFKA_BROKER_CONFIG);
            if (kafkaBroker != null) {
              String listenersPropertyValue = kafkaBroker.getProperties().get("listeners");
              if (StringUtils.isNotEmpty(listenersPropertyValue)) {
                String newListenersPropertyValue = listenersPropertyValue.replaceAll("\\bPLAINTEXT\\b", "PLAINTEXTSASL");
                if(!newListenersPropertyValue.equals(listenersPropertyValue)) {
                  updateConfigurationProperties(KAFKA_BROKER_CONFIG, Collections.singletonMap("listeners", newListenersPropertyValue), true, false);
                }
              }
            }
          }
        }
      }
    }
  }

  /**
   * Adds the {@value #HRC_IS_BACKGROUND_COLUMN} column to the
   * {@value #HOST_ROLE_COMMAND_TABLE} table.
   *
   * @throws SQLException
   */
  private void addBackgroundColumnToHostRoleCommand() throws SQLException {
    dbAccessor.addColumn(HOST_ROLE_COMMAND_TABLE,
        new DBColumnInfo(HRC_IS_BACKGROUND_COLUMN, Short.class, null, 0, false));
  }

  /**
   * Moves the {@value #CLUSTER_HOST_INFO_COLUMN} column from {@value #STAGE_TABLE} table to the
   * {@value #REQUEST_TABLE} table
   *
   *
   * @throws SQLException
   */
  private void moveClusterHostColumnFromStageToRequest() throws SQLException {
    DBColumnInfo sourceColumn = new DBColumnInfo(CLUSTER_HOST_INFO_COLUMN, byte[].class, null, null, false);
    DBColumnInfo targetColumn = new DBColumnInfo(CLUSTER_HOST_INFO_COLUMN, byte[].class, null, null, false);

    dbAccessor.moveColumnToAnotherTable(STAGE_TABLE, sourceColumn, REQUEST_ID_COLUMN, REQUEST_TABLE, targetColumn,
      REQUEST_ID_COLUMN, false);
  }
}
