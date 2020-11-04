/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processing.deployment;

import static io.zeebe.engine.state.instance.TimerInstance.NO_ELEMENT_INSTANCE;

import io.zeebe.engine.processing.common.CatchEventBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.common.ExpressionProcessor.EvaluationException;
import io.zeebe.engine.processing.common.Failure;
import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEventElement;
import io.zeebe.engine.processing.deployment.model.element.ExecutableStartEvent;
import io.zeebe.engine.processing.deployment.transform.DeploymentTransformer;
import io.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.zeebe.engine.processing.streamprocessor.TypedRecordProcessor;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffectProducer;
import io.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.engine.state.deployment.WorkflowState;
import io.zeebe.engine.state.instance.TimerInstance;
import io.zeebe.model.bpmn.util.time.Timer;
import io.zeebe.protocol.impl.record.value.deployment.DeploymentRecord;
import io.zeebe.protocol.impl.record.value.deployment.Workflow;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.intent.DeploymentIntent;
import io.zeebe.util.Either;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;

public final class TransformingDeploymentCreateProcessor
    implements TypedRecordProcessor<DeploymentRecord> {

  private static final String COULD_NOT_CREATE_TIMER_MESSAGE =
      "Expected to create timer for start event, but encountered the following error: %s";
  private final DeploymentTransformer deploymentTransformer;
  private final WorkflowState workflowState;
  private final CatchEventBehavior catchEventBehavior;
  private final KeyGenerator keyGenerator;
  private final ExpressionProcessor expressionProcessor;

  public TransformingDeploymentCreateProcessor(
      final ZeebeState zeebeState,
      final CatchEventBehavior catchEventBehavior,
      final ExpressionProcessor expressionProcessor) {
    workflowState = zeebeState.getWorkflowState();
    keyGenerator = zeebeState.getKeyGenerator();
    deploymentTransformer = new DeploymentTransformer(zeebeState, expressionProcessor);
    this.catchEventBehavior = catchEventBehavior;
    this.expressionProcessor = expressionProcessor;
  }

  @Override
  public void processRecord(
      final TypedRecord<DeploymentRecord> command,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffect) {
    final DeploymentRecord deploymentEvent = command.getValue();

    final boolean accepted = deploymentTransformer.transform(deploymentEvent);
    if (accepted) {
      final long key = keyGenerator.nextKey();
      workflowState.putDeployment(deploymentEvent);

      try {
        createTimerIfTimerStartEvent(command, streamWriter);
      } catch (final RuntimeException e) {
        final String reason = String.format(COULD_NOT_CREATE_TIMER_MESSAGE, e.getMessage());
        responseWriter.writeRejectionOnCommand(command, RejectionType.PROCESSING_ERROR, reason);
        streamWriter.appendRejection(command, RejectionType.PROCESSING_ERROR, reason);
        return;
      }

      responseWriter.writeEventOnCommand(key, DeploymentIntent.CREATED, deploymentEvent, command);
      streamWriter.appendFollowUpEvent(key, DeploymentIntent.CREATED, deploymentEvent);
    } else {
      responseWriter.writeRejectionOnCommand(
          command,
          deploymentTransformer.getRejectionType(),
          deploymentTransformer.getRejectionReason());
      streamWriter.appendRejection(
          command,
          deploymentTransformer.getRejectionType(),
          deploymentTransformer.getRejectionReason());
    }
  }

  private void createTimerIfTimerStartEvent(
      final TypedRecord<DeploymentRecord> record, final TypedStreamWriter streamWriter) {
    for (final Workflow workflow : record.getValue().workflows()) {
      final List<ExecutableStartEvent> startEvents =
          workflowState.getWorkflowByKey(workflow.getKey()).getWorkflow().getStartEvents();
      boolean hasAtLeastOneTimer = false;

      unsubscribeFromPreviousTimers(streamWriter, workflow);

      for (final ExecutableCatchEventElement startEvent : startEvents) {
        if (startEvent.isTimer()) {
          hasAtLeastOneTimer = true;

          // There are no variables when there is no process instance yet,
          // we use a negative scope key to indicate this
          final long scopeKey = -1L;
          final Either<Failure, Timer> timerOrError =
              startEvent.getTimerFactory().apply(expressionProcessor, scopeKey);
          if (timerOrError.isLeft()) {
            // todo(#4323): deal with this exceptional case without throwing an exception
            throw new EvaluationException(timerOrError.getLeft().getMessage());
          }
          catchEventBehavior.subscribeToTimerEvent(
              NO_ELEMENT_INSTANCE,
              NO_ELEMENT_INSTANCE,
              workflow.getKey(),
              startEvent.getId(),
              timerOrError.get(),
              streamWriter);
        }
      }

      if (hasAtLeastOneTimer) {
        workflowState
            .getEventScopeInstanceState()
            .createIfNotExists(workflow.getKey(), Collections.emptyList());
      }
    }
  }

  private void unsubscribeFromPreviousTimers(
      final TypedStreamWriter streamWriter, final Workflow workflow) {
    workflowState
        .getTimerState()
        .forEachTimerForElementInstance(
            NO_ELEMENT_INSTANCE,
            timer -> unsubscribeFromPreviousTimer(streamWriter, workflow, timer));
  }

  private void unsubscribeFromPreviousTimer(
      final TypedStreamWriter streamWriter, final Workflow workflow, final TimerInstance timer) {
    final DirectBuffer timerBpmnId =
        workflowState.getWorkflowByKey(timer.getWorkflowKey()).getBpmnProcessId();

    if (timerBpmnId.equals(workflow.getBpmnProcessIdBuffer())) {
      catchEventBehavior.unsubscribeFromTimerEvent(timer, streamWriter);
    }
  }
}
