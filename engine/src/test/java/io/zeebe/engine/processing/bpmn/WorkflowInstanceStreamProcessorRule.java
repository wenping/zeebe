/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processing.bpmn;

import static io.zeebe.test.util.TestUtil.waitUntil;
import static io.zeebe.util.buffer.BufferUtil.wrapString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.zeebe.el.ExpressionLanguageFactory;
import io.zeebe.engine.processing.WorkflowEventProcessors;
import io.zeebe.engine.processing.common.CatchEventBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.job.JobEventProcessors;
import io.zeebe.engine.processing.message.command.SubscriptionCommandSender;
import io.zeebe.engine.processing.streamprocessor.CopiedRecords;
import io.zeebe.engine.processing.streamprocessor.StreamProcessorLifecycleAware;
import io.zeebe.engine.processing.timer.DueDateTimerChecker;
import io.zeebe.engine.state.deployment.WorkflowState;
import io.zeebe.engine.util.Records;
import io.zeebe.engine.util.StreamProcessorRule;
import io.zeebe.engine.util.TypedRecordStream;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.model.bpmn.instance.Process;
import io.zeebe.protocol.Protocol;
import io.zeebe.protocol.impl.record.UnifiedRecordValue;
import io.zeebe.protocol.impl.record.value.deployment.DeploymentRecord;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.protocol.impl.record.value.message.WorkflowInstanceSubscriptionRecord;
import io.zeebe.protocol.impl.record.value.timer.TimerRecord;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceCreationRecord;
import io.zeebe.protocol.impl.record.value.workflowinstance.WorkflowInstanceRecord;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.protocol.record.intent.Intent;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.TimerIntent;
import io.zeebe.protocol.record.intent.WorkflowInstanceCreationIntent;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.protocol.record.value.deployment.ResourceType;
import io.zeebe.test.util.TestUtil;
import io.zeebe.util.buffer.BufferUtil;
import io.zeebe.util.sched.ActorControl;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Rule;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;

public final class WorkflowInstanceStreamProcessorRule extends ExternalResource
    implements StreamProcessorLifecycleAware {

  public static final int VERSION = 1;
  public static final int WORKFLOW_KEY = 123;
  public static final int DEPLOYMENT_KEY = 1;
  @Rule public TemporaryFolder folder = new TemporaryFolder();
  private final StreamProcessorRule environmentRule;
  private SubscriptionCommandSender mockSubscriptionCommandSender;

  private WorkflowState workflowState;
  private ActorControl actor;

  public WorkflowInstanceStreamProcessorRule(final StreamProcessorRule streamProcessorRule) {
    environmentRule = streamProcessorRule;
  }

  public SubscriptionCommandSender getMockSubscriptionCommandSender() {
    return mockSubscriptionCommandSender;
  }

  @Override
  protected void before() {
    mockSubscriptionCommandSender = mock(SubscriptionCommandSender.class);

    when(mockSubscriptionCommandSender.openMessageSubscription(
            anyInt(), anyLong(), anyLong(), any(), any(), any(), anyBoolean()))
        .thenReturn(true);
    when(mockSubscriptionCommandSender.correlateMessageSubscription(
            anyInt(), anyLong(), anyLong(), any(), any()))
        .thenReturn(true);
    when(mockSubscriptionCommandSender.closeMessageSubscription(
            anyInt(), anyLong(), anyLong(), any(DirectBuffer.class)))
        .thenReturn(true);
    when(mockSubscriptionCommandSender.rejectCorrelateMessageSubscription(
            anyLong(), any(), anyLong(), any(), any()))
        .thenReturn(true);

    environmentRule.startTypedStreamProcessor(
        (typedRecordProcessors, processingContext) -> {
          final var zeebeState = processingContext.getZeebeState();
          actor = processingContext.getActor();
          workflowState = zeebeState.getWorkflowState();

          final var variablesState =
              zeebeState.getWorkflowState().getElementInstanceState().getVariablesState();
          final ExpressionProcessor expressionProcessor =
              new ExpressionProcessor(
                  ExpressionLanguageFactory.createExpressionLanguage(),
                  variablesState::getVariable);

          WorkflowEventProcessors.addWorkflowProcessors(
              zeebeState,
              expressionProcessor,
              typedRecordProcessors,
              mockSubscriptionCommandSender,
              new CatchEventBehavior(
                  zeebeState, expressionProcessor, mockSubscriptionCommandSender, 1),
              new DueDateTimerChecker(workflowState));

          JobEventProcessors.addJobProcessors(
              typedRecordProcessors, zeebeState, type -> {}, Integer.MAX_VALUE);
          typedRecordProcessors.withListener(this);
          return typedRecordProcessors;
        });
  }

  public void deploy(final BpmnModelInstance modelInstance, final int version) {
    final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    Bpmn.writeModelToStream(outStream, modelInstance);
    final DirectBuffer xmlBuffer = new UnsafeBuffer(outStream.toByteArray());

    final DeploymentRecord record = new DeploymentRecord();
    final DirectBuffer resourceName = wrapString("resourceName");

    final Process process = modelInstance.getModelElementsByType(Process.class).iterator().next();

    record
        .resources()
        .add()
        .setResource(xmlBuffer)
        .setResourceName(resourceName)
        .setResourceType(ResourceType.BPMN_XML);

    record
        .workflows()
        .add()
        .setKey(WORKFLOW_KEY)
        .setResourceName(resourceName)
        .setBpmnProcessId(BufferUtil.wrapString(process.getId()))
        .setVersion(version);

    actor.call(() -> workflowState.putDeployment(record)).join();
  }

  public void deploy(final BpmnModelInstance modelInstance) {
    deploy(modelInstance, VERSION);
  }

  public Record<WorkflowInstanceRecord> createAndReceiveWorkflowInstance(
      final Function<WorkflowInstanceCreationRecord, WorkflowInstanceCreationRecord> transformer) {
    final Record<WorkflowInstanceCreationRecord> createdRecord =
        createWorkflowInstance(transformer);

    return awaitAndGetFirstWorkflowInstanceRecord(
        r ->
            r.getIntent() == WorkflowInstanceIntent.ELEMENT_ACTIVATING
                && r.getKey() == createdRecord.getValue().getWorkflowInstanceKey());
  }

  public Record<WorkflowInstanceCreationRecord> createWorkflowInstance(
      final Function<WorkflowInstanceCreationRecord, WorkflowInstanceCreationRecord> transformer) {
    final long position =
        environmentRule.writeCommand(
            WorkflowInstanceCreationIntent.CREATE,
            transformer.apply(new WorkflowInstanceCreationRecord()));

    return awaitAndGetFirstRecord(
        ValueType.WORKFLOW_INSTANCE_CREATION,
        (e) ->
            e.getSourceRecordPosition() == position
                && e.getIntent() == WorkflowInstanceCreationIntent.CREATED,
        new WorkflowInstanceCreationRecord());
  }

  public void completeFirstJob() {
    final Record<JobRecord> createCommand = awaitAndGetFirstRecordInState(JobIntent.CREATE);

    final long jobKey = environmentRule.writeEvent(JobIntent.CREATED, createCommand.getValue());
    environmentRule.writeEvent(jobKey, JobIntent.COMPLETED, createCommand.getValue());
  }

  public Record<WorkflowInstanceRecord> awaitAndGetFirstWorkflowInstanceRecord(
      final Predicate<Record<WorkflowInstanceRecord>> matcher) {
    return awaitAndGetFirstRecord(
        ValueType.WORKFLOW_INSTANCE, matcher, WorkflowInstanceRecord.class);
  }

  public <T extends UnifiedRecordValue> Record<T> awaitAndGetFirstRecord(
      final ValueType valueType, final Predicate<Record<T>> matcher, final Class<T> valueClass) {
    return TestUtil.doRepeatedly(
            () ->
                environmentRule
                    .events()
                    .filter(r -> Records.isRecordOfType(r, valueType))
                    .map(
                        e ->
                            (Record<T>)
                                CopiedRecords.createCopiedRecord(Protocol.DEPLOYMENT_PARTITION, e))
                    .filter(matcher)
                    .findFirst())
        .until(Optional::isPresent)
        .orElse(null);
  }

  public <T extends UnifiedRecordValue> Record<T> awaitAndGetFirstRecord(
      final ValueType valueType, final Function<Record<T>, Boolean> matcher, final T value) {
    return TestUtil.doRepeatedly(
            () ->
                environmentRule
                    .events()
                    .filter(r -> Records.isRecordOfType(r, valueType))
                    .map(
                        e ->
                            (Record<T>)
                                CopiedRecords.createCopiedRecord(Protocol.DEPLOYMENT_PARTITION, e))
                    .filter(e -> matcher.apply(e))
                    .findFirst())
        .until(Optional::isPresent)
        .orElse(null);
  }

  private Record<JobRecord> awaitAndGetFirstRecordInState(final JobIntent state) {
    awaitFirstRecordInState(state);
    return environmentRule.events().onlyJobRecords().withIntent(state).findFirst().get();
  }

  private void awaitFirstRecordInState(final Intent state) {
    waitUntil(() -> environmentRule.events().withIntent(state).findFirst().isPresent());
  }

  public Record<WorkflowInstanceSubscriptionRecord> awaitAndGetFirstSubscriptionRejection() {
    waitUntil(
        () ->
            environmentRule
                .events()
                .onlyWorkflowInstanceSubscriptionRecords()
                .onlyRejections()
                .findFirst()
                .isPresent());

    return environmentRule
        .events()
        .onlyWorkflowInstanceSubscriptionRecords()
        .onlyRejections()
        .findFirst()
        .get();
  }

  public Record<WorkflowInstanceRecord> awaitElementInState(
      final String elementId, final WorkflowInstanceIntent intent) {
    final DirectBuffer elementIdAsBuffer = BufferUtil.wrapString(elementId);

    return TestUtil.doRepeatedly(
            () ->
                environmentRule
                    .events()
                    .onlyWorkflowInstanceRecords()
                    .withIntent(intent)
                    .filter(r -> elementIdAsBuffer.equals(r.getValue().getElementIdBuffer()))
                    .findFirst())
        .until(o -> o.isPresent())
        .get();
  }

  public Record<TimerRecord> awaitTimerInState(final String timerId, final TimerIntent state) {
    final Supplier<TypedRecordStream<TimerRecord>> lookupStream =
        () ->
            environmentRule
                .events()
                .onlyTimerRecords()
                .filter(r -> r.getValue().getTargetElementId().equals(timerId))
                .withIntent(state);

    waitUntil(() -> lookupStream.get().findFirst().isPresent());
    return lookupStream.get().findFirst().get();
  }

  public Record<JobRecord> awaitJobInState(final String activityId, final JobIntent state) {
    final DirectBuffer activityIdBuffer = wrapString(activityId);
    final Supplier<TypedRecordStream<JobRecord>> lookupStream =
        () ->
            environmentRule
                .events()
                .onlyJobRecords()
                .filter(r -> r.getValue().getElementIdBuffer().equals(activityIdBuffer))
                .withIntent(state);

    waitUntil(() -> lookupStream.get().findFirst().isPresent());
    return lookupStream.get().findFirst().get();
  }

  @Override
  public void onClose() {
    actor = null;
  }
}
