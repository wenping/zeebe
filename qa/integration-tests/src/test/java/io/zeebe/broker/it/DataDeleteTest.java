/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
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
package io.zeebe.broker.it;

import io.zeebe.broker.system.configuration.DataCfg;
import io.zeebe.broker.system.configuration.ExporterCfg;
import io.zeebe.broker.test.EmbeddedBrokerRule;
import io.zeebe.exporter.api.context.Controller;
import io.zeebe.exporter.api.record.Record;
import io.zeebe.exporter.api.spi.Exporter;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.protocol.intent.MessageIntent;
import io.zeebe.test.util.TestUtil;
import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.Timeout;

public class DataDeleteTest {
  private static final int MAX_SNAPSHOTS = 1;
  private static final String SNAPSHOT_PERIOD = "30s";
  private static final String PROCESS = "process";
  private static final BpmnModelInstance WORKFLOW =
      Bpmn.createExecutableProcess(PROCESS).startEvent("start").endEvent("end").done();

  public EmbeddedBrokerRule brokerRule =
      new EmbeddedBrokerRule(
          brokerCfg -> {
            final DataCfg data = brokerCfg.getData();
            data.setMaxSnapshots(MAX_SNAPSHOTS);
            data.setSnapshotPeriod(SNAPSHOT_PERIOD);
            data.setDefaultLogSegmentSize("8k");
            data.setIndexBlockSize("2K");

            final List<ExporterCfg> exporters = brokerCfg.getExporters();
            final ExporterCfg exporterCfg = new ExporterCfg();
            exporterCfg.setClassName(TestExporter.class.getName());
            exporterCfg.setId("data-delete-test-exporter");
            exporters.add(exporterCfg);
          });
  public GrpcClientRule clientRule = new GrpcClientRule(brokerRule);

  @Rule public RuleChain ruleChain = RuleChain.outerRule(brokerRule).around(clientRule);
  @Rule public Timeout timeout = new Timeout(60, TimeUnit.SECONDS);

  @Test
  public void shouldDeleteDataWithExporters() {
    // given
    final String rootPath = brokerRule.getBrokerCfg().getData().getDirectories().get(0);
    final String snapshotDirPath = rootPath + "/partition-1/state/1_zb-stream-processor/snapshots";
    final String segmentsDirPath = rootPath + "/partition-1/segments";
    brokerRule.getClock().pinCurrentTime();

    final long workflowKey = clientRule.deployWorkflow(WORKFLOW);
    final File segmentsDir = new File(segmentsDirPath);
    int messagesSent = 0;

    while (segmentsDir.list().length <= 2) {
      clientRule
          .getClient()
          .newPublishMessageCommand()
          .messageName("msg")
          .correlationKey("c")
          .send()
          .join();
      ++messagesSent;
    }

    // when
    final int finalMessagesSent = messagesSent;
    TestUtil.waitUntil(
        () ->
            TestExporter.records.stream()
                    .filter(r -> r.getMetadata().getIntent() == MessageIntent.PUBLISHED)
                    .limit(finalMessagesSent)
                    .count()
                == finalMessagesSent);

    final int segments = segmentsDir.list().length;

    brokerRule.getClock().addTime(Duration.ofSeconds(30));
    final File snapshotsDir = new File(snapshotDirPath);
    TestUtil.waitUntil(
        () -> Arrays.stream(snapshotsDir.listFiles()).anyMatch(f -> !f.getName().equals("/tmp")));

    // then
    TestUtil.waitUntil(() -> segmentsDir.listFiles().length < segments);
  }

  public static class TestExporter implements Exporter {
    private Controller controller;
    static List<Record> records = new CopyOnWriteArrayList<>();

    @Override
    public void export(final Record record) {
      records.add(record);
      controller.updateLastExportedRecordPosition(record.getPosition());
    }

    @Override
    public void open(final Controller controller) {
      this.controller = controller;
    }
  }
}