/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.transport.impl;

import io.atomix.cluster.messaging.MessagingException;
import io.atomix.cluster.messaging.MessagingService;
import io.zeebe.transport.ClientRequest;
import io.zeebe.transport.ClientTransport;
import io.zeebe.util.ZbLogger;
import io.zeebe.util.sched.Actor;
import io.zeebe.util.sched.future.ActorFuture;
import io.zeebe.util.sched.future.CompletableActorFuture;
import java.net.ConnectException;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;

public final class AtomixClientTransportAdapter extends Actor implements ClientTransport {

  private static final Logger LOG = new ZbLogger(AtomixClientTransportAdapter.class);
  private static final Duration RETRY_DELAY = Duration.ofMillis(10);
  private static final String NO_REMOTE_ADDRESS_FOUND_ERROR_MESSAGE =
      "Failed to send request to %s, no remote address found.";

  private final MessagingService messagingService;

  public AtomixClientTransportAdapter(final MessagingService messagingService) {
    this.messagingService = messagingService;
  }

  @Override
  public ActorFuture<DirectBuffer> sendRequestWithRetry(
      final Supplier<String> nodeAddressSupplier,
      final Predicate<DirectBuffer> responseValidator,
      final ClientRequest clientRequest,
      final Duration timeout) {
    return sendRequestInternal(
        nodeAddressSupplier, responseValidator, clientRequest, true, timeout);
  }

  @Override
  public ActorFuture<DirectBuffer> sendRequest(
      final Supplier<String> nodeAddressSupplier,
      final ClientRequest clientRequest,
      final Duration timeout) {
    return sendRequestInternal(nodeAddressSupplier, r -> true, clientRequest, false, timeout);
  }

  private ActorFuture<DirectBuffer> sendRequestInternal(
      final Supplier<String> nodeAddressSupplier,
      final Predicate<DirectBuffer> responseValidator,
      final ClientRequest clientRequest,
      final boolean shouldRetry,
      final Duration timeout) {

    // copy once
    final var length = clientRequest.getLength();
    final var requestBytes = new byte[length];
    final var buffer = new UnsafeBuffer(requestBytes);
    clientRequest.write(buffer, 0);

    final var partitionId = clientRequest.getPartitionId();

    final var requestFuture = new CompletableActorFuture<DirectBuffer>();
    final var requestContext =
        new RequestContext(
            requestFuture,
            nodeAddressSupplier,
            partitionId,
            requestBytes,
            responseValidator,
            shouldRetry,
            timeout);
    actor.call(
        () -> {
          final var scheduledTimer = actor.runDelayed(timeout, () -> timeoutFuture(requestContext));
          requestContext.setScheduledTimer(scheduledTimer);
          tryToSend(requestContext);
        });

    return requestFuture;
  }

  private void tryToSend(final RequestContext requestContext) {
    if (requestContext.isDone()) {
      LOG.trace("Request {} is already done", requestContext.hashCode());
      return;
    }

    final var calculateTimeout = requestContext.calculateTimeout();
    if (calculateTimeout.toMillis() <= 0L) {
      LOG.trace(
          "Request {} reached timeout of {}, current calculation {}",
          requestContext.hashCode(),
          requestContext.getTimeout(),
          calculateTimeout);
      // we reached the timeout
      // our request future will be completedExceptionally from the scheduled timeout job
      return;
    }

    final var nodeAddress = requestContext.getNodeAddress();
    if (nodeAddress == null) {
      if (requestContext.shouldRetry()) {
        LOG.trace(
            "No target address for request {}, retry after {}.",
            requestContext.hashCode(),
            RETRY_DELAY);
        actor.runDelayed(RETRY_DELAY, () -> tryToSend(requestContext));
      } else {
        LOG.trace(
            "No target address for request {}, will fail request.", requestContext.hashCode());
        requestContext.completeExceptionally(
            new ConnectException(
                String.format(
                    NO_REMOTE_ADDRESS_FOUND_ERROR_MESSAGE, requestContext.getTopicName())));
      }
      return;
    }

    LOG.trace(
        "Send request {} to {} with topic {}",
        requestContext.hashCode(),
        requestContext.getNodeAddress(),
        requestContext.getTopicName());

    final var requestBytes = requestContext.getRequestBytes();
    messagingService
        .sendAndReceive(nodeAddress, requestContext.getTopicName(), requestBytes, calculateTimeout)
        .whenComplete(
            (response, errorOnRequest) ->
                actor.run(() -> handleResponse(requestContext, response, errorOnRequest)));
  }

  private void handleResponse(
      final RequestContext requestContext, final byte[] response, final Throwable errorOnRequest) {
    if (requestContext.isDone()) {
      LOG.trace("Handle response, but request {} is already done", requestContext.hashCode());
      return;
    }

    if (errorOnRequest == null) {

      final var responseBuffer = new UnsafeBuffer(response);
      if (requestContext.verifyResponse(responseBuffer)) {
        LOG.trace("Got valid response for request {}.", requestContext.hashCode());
        requestContext.complete(responseBuffer);
      } else {
        LOG.trace(
            "Got invalid response for request {}, retry in {}.",
            requestContext.hashCode(),
            RETRY_DELAY);
        // no valid response - retry in respect of the timeout
        actor.runDelayed(RETRY_DELAY, () -> tryToSend(requestContext));
      }
    } else {
      // normally the root exception is a completion exception
      // and the cause is either connect or non remote handler
      final var cause = errorOnRequest.getCause();
      if ((exceptionShowsConnectionIssue(errorOnRequest) || exceptionShowsConnectionIssue(cause))
          && requestContext.shouldRetry()) {

        LOG.trace(
            "Request {} failed, but will retry after delay {}",
            requestContext.hashCode(),
            RETRY_DELAY,
            errorOnRequest);
        // no registered subscription yet
        actor.runDelayed(RETRY_DELAY, () -> tryToSend(requestContext));
      } else {

        LOG.trace("Request {} failed, will not retry!", requestContext.hashCode(), errorOnRequest);
        requestContext.completeExceptionally(errorOnRequest);
      }
    }
  }

  private boolean exceptionShowsConnectionIssue(final Throwable throwable) {
    return throwable instanceof ConnectException
        || throwable instanceof MessagingException.NoRemoteHandler;
  }

  private void timeoutFuture(final RequestContext requestContext) {
    if (requestContext.isDone()) {
      return;
    }

    requestContext.timeout();
  }
}
