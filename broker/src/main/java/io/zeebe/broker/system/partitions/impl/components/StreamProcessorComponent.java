/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.system.partitions.impl.components;

import io.zeebe.broker.system.partitions.Component;
import io.zeebe.broker.system.partitions.PartitionContext;
import io.zeebe.engine.processing.streamprocessor.StreamProcessor;
import io.zeebe.engine.state.ZeebeState;
import io.zeebe.util.sched.ActorControl;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;

public class StreamProcessorComponent implements Component<StreamProcessor> {

  @Override
  public ActorFuture<StreamProcessor> open(final PartitionContext context) {
    final StreamProcessor streamProcessor = createStreamProcessor(context);

    final ActorFuture<Void> streamProcFuture = streamProcessor.openAsync();
    final CompletableActorFuture<StreamProcessor> future = new CompletableActorFuture<>();

    streamProcFuture.onComplete(
        (nothing, err) -> {
          if (err != null) {
            future.completeExceptionally(err);
          } else {
            future.complete(streamProcessor);
          }
        });

    return future;
  }

  @Override
  public ActorFuture<Void> close(final PartitionContext context) {
    context.getComponentHealthMonitor().removeComponent(context.getStreamProcessor().getName());
    final ActorFuture<Void> future = context.getStreamProcessor().closeAsync();
    context.setStreamProcessor(null);
    return future;
  }

  @Override
  public ActorFuture<Void> onOpen(
      final PartitionContext context, final StreamProcessor streamProcessor) {
    context.setStreamProcessor(streamProcessor);

    if (!context.shouldProcess()) {
      streamProcessor.pauseProcessing();
    }

    context
        .getComponentHealthMonitor()
        .registerComponent(streamProcessor.getName(), streamProcessor);

    return CompletableActorFuture.completed(null);
  }

  @Override
  public String getName() {
    return "StreamProcessor";
  }

  private StreamProcessor createStreamProcessor(final PartitionContext state) {
    return StreamProcessor.builder()
        .logStream(state.getLogStream())
        .actorScheduler(state.getScheduler())
        .zeebeDb(state.getZeebeDb())
        .nodeId(state.getNodeId())
        .commandResponseWriter(state.getCommandApiService().newCommandResponseWriter())
        .onProcessedListener(
            state.getCommandApiService().getOnProcessedListener(state.getPartitionId()))
        .streamProcessorFactory(
            processingContext -> {
              final ActorControl actor = processingContext.getActor();
              final ZeebeState zeebeState = processingContext.getZeebeState();
              return state
                  .getTypedRecordProcessorsFactory()
                  .createTypedStreamProcessor(actor, zeebeState, processingContext);
            })
        .build();
  }
}
