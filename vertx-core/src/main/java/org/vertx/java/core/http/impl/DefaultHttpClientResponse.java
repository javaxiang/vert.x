/*
 * Copyright (c) 2011-2013 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package org.vertx.java.core.http.impl;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import org.vertx.java.core.Handler;
import org.vertx.java.core.MultiMap;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.VoidHandler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.net.NetSocket;

import java.util.*;

/**
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class DefaultHttpClientResponse implements HttpClientResponse  {

  private final int statusCode;
  private final String statusMessage;
  private final DefaultHttpClientRequest request;
  private final Vertx vertx;
  private final ClientConnection conn;

  private Handler<Buffer> dataHandler;
  private Handler<Void> endHandler;
  private Handler<Throwable> exceptionHandler;
  private final HttpResponse response;
  private LastHttpContent trailer;
  private boolean paused;
  private Queue<Buffer> pausedChunks;
  private boolean hasPausedEnd;
  private LastHttpContent pausedTrailer;
  private NetSocket netSocket;

  // Cache these for performance
  private MultiMap headers;
  private MultiMap trailers;
  private List<String> cookies;

  DefaultHttpClientResponse(Vertx vertx, DefaultHttpClientRequest request, ClientConnection conn, HttpResponse response) {
    this.vertx = vertx;
    this.statusCode = response.getStatus().code();
    this.statusMessage = response.getStatus().reasonPhrase();
    this.request = request;
    this.conn = conn;
    this.response = response;
  }

  @Override
  public int statusCode() {
    return statusCode;
  }

  @Override
  public String statusMessage() {
    return statusMessage;
  }

  @Override
  public MultiMap headers() {
    if (headers == null) {
      headers = new HttpHeadersAdapter(response.headers());
    }
    return headers;
  }

  @Override
  public MultiMap trailers() {
    if (trailers == null) {
      trailers = new HttpHeadersAdapter(new DefaultHttpHeaders());
    }
    return trailers;
  }

  @Override
  public List<String> cookies() {
    if (cookies == null) {
      cookies = new ArrayList<>();
      cookies.addAll(response.headers().getAll(org.vertx.java.core.http.HttpHeaders.SET_COOKIE));
      if (trailer != null) {
        cookies.addAll(trailer.trailingHeaders().getAll(org.vertx.java.core.http.HttpHeaders.SET_COOKIE));
      }
    }
    return cookies;
  }

  @Override
  public HttpClientResponse dataHandler(Handler<Buffer> dataHandler) {
    this.dataHandler = dataHandler;
    return this;
  }

  @Override
  public HttpClientResponse endHandler(Handler<Void> endHandler) {
    this.endHandler = endHandler;
    return this;
  }

  @Override
  public HttpClientResponse exceptionHandler(Handler<Throwable> exceptionHandler) {
    this.exceptionHandler = exceptionHandler;
    return this;
  }

  @Override
  public HttpClientResponse pause() {
    paused = true;
    conn.doPause();
    return this;
  }

  @Override
  public HttpClientResponse resume() {
    doResume();
    paused = false;
    conn.doResume();
    return this;
  }

  @Override
  public HttpClientResponse bodyHandler(final Handler<Buffer> bodyHandler) {
    final Buffer body = new Buffer();
    dataHandler(new Handler<Buffer>() {
      public void handle(Buffer buff) {
        body.appendBuffer(buff);
      }
    });
    endHandler(new VoidHandler() {
      public void handle() {
        bodyHandler.handle(body);
      }
    });
    return this;
  }

  private void doResume() {
    if (pausedChunks != null) {
      Buffer chunk;
      while ((chunk = pausedChunks.poll()) != null) {
        final Buffer theChunk = chunk;
        vertx.runOnContext(new VoidHandler() {
          @Override
          protected void handle() {
            handleChunk0(theChunk);
          }
        });
      }
    }
    if (hasPausedEnd) {
      final LastHttpContent theTrailer = pausedTrailer;
      vertx.runOnContext(new VoidHandler() {
        @Override
        protected void handle() {
          handleEnd0(theTrailer);
        }
      });
      hasPausedEnd = false;
      pausedTrailer = null;
    }
  }

  void handleChunk(Buffer data) {
    if (paused) {
      if (pausedChunks == null) {
        pausedChunks = new ArrayDeque<>();
      }
      pausedChunks.add(data);
    } else {
      // consume all previous paused chunks if any are left
      // This is needed as netty may trigger an IO operation in between
      // handling submitted tasks on the EventLoop.
      if (pausedChunks != null) {
        Buffer chunk;
        while ((chunk = pausedChunks.poll()) != null) {
          handleChunk0(chunk);
        }
      }
      handleChunk0(data);
    }
  }

  private void handleChunk0(Buffer data) {
    request.dataReceived();
    if (dataHandler != null) {
      dataHandler.handle(data);
    }
  }

  void handleEnd(LastHttpContent trailer) {
    if (paused) {
      hasPausedEnd = true;
      pausedTrailer = trailer;
    } else {
      handleEnd0(trailer);
    }
  }

  private void handleEnd0(LastHttpContent trailer) {
    this.trailer = trailer;
    trailers = new HttpHeadersAdapter(trailer.trailingHeaders());
    if (endHandler != null) {
      endHandler.handle(null);
    }
  }

  void handleException(Throwable e) {
    if (exceptionHandler != null) {
      exceptionHandler.handle(e);
    }
  }

  @Override
  public NetSocket netSocket() {
    if (netSocket == null) {
      netSocket = conn.createNetSocket();
    }
    return netSocket;
  }
}
