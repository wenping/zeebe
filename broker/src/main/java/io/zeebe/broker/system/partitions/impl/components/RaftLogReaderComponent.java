/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.system.partitions.impl.components;

import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.storage.journal.JournalReader.Mode;
import io.zeebe.broker.system.partitions.Component;
import io.zeebe.broker.system.partitions.PartitionContext;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;

public class RaftLogReaderComponent implements Component<RaftLogReader> {

  @Override
  public ActorFuture<RaftLogReader> open(final PartitionContext context) {
    final var reader = context.getRaftPartition().getServer().openReader(-1, Mode.COMMITS);
    return CompletableActorFuture.completed(reader);
  }

  @Override
  public ActorFuture<Void> close(final PartitionContext context) {
    context.getRaftLogReader().close();
    context.setRaftLogReader(null);
    return CompletableActorFuture.completed(null);
  }

  @Override
  public ActorFuture<Void> onOpen(final PartitionContext context, final RaftLogReader reader) {
    context.setRaftLogReader(reader);
    return CompletableActorFuture.completed(null);
  }

  @Override
  public String getName() {
    return "RaftLogReader";
  }
}
