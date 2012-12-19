
/*
 * Copyright (c) 2012-2012 Malhar, Inc.
 * All rights reserved.
 */

/**
 *
 * <b>com.malhartech.stream</b> package contains all code related to various implementations of Stream interface<p>
 * <br>
 * A stream is a logical unit of a dag that defines the connection between
 * a node and list of listener operators. Stream has the following properties in Malhar's streaming platform<br>
 * - One writer node<br>
 * - Any number of listener operators<br>
 * - Context as defined by the properties specified in the dag<br>
 * A stream definition in the dag is a logical definition. Multiple logical listerner operators means that the emitted tuple
 * would reach each of them. Partitioning is done when a single
 * logical listener node partitions into multilpe physical operators. This may happen due to initial user
 * specification, or dynamic run time constraint enforcement. In such a scenerio the logical stream gets partitioned
 * into physical streams. Each physical stream would retain the
 * characteristics of the logical node (one writer, multiple readers, and context).<br>
 * <br>
 * The streams included in com.malhartech.stream include<br>
 * <br>
 * <br><b>Buffer Server Streams</b><br>
 * <b>{@link com.malhartech.stream.BufferServerInputStream}</b>: extends {@link com.malhartech.stream.SocketInputStream},
 * takes data from buffer server into the node. Every logical stream will have at least two such
 * objects ({@link com.malhartech.stream.BufferServerInputStream}
 *  and {@link com.malhartech.stream.BufferServerOutputStream}). If the logical stream gets partitioned
 * into physical streams then each of these physical streams will have these objects. Inlined version of
 *  a logical stream does not go through the buffer server and hence would not have
 * {@link com.malhartech.stream.BufferServerInputStream} and {@link com.malhartech.stream.BufferServerOutputStream} objects<br>
 * <b>{@link com.malhartech.stream.BufferServerOutputStream}</b>: extends {@link com.malhartech.stream.SocketOutputStream}
 * and in conjunction with {@link com.malhartech.stream.BufferServerInputStream} forms a complete stream
 * in a node->buffer server->node path<br>
 * <br><b>Inline Stream (Within a Hadoop Container)</b><br>
 * <b>{@link com.malhartech.stream.InlineStream}</b>: Streams data between two operators in inline mode. This implementation of
 * {@link com.malhartech.engine.Stream} and {{@link com.malhartech.api.Sink}
 * interface does not have connection to BufferServer and cannot be persisted.<br>
 *
 * <b>{@link com.malhartech.stream.MuxStream}</b>: <br>
 * <b>{@link com.malhartech.stream.PartitionAwareSink}</b>: <br>
 *
 * <br><b>Socket Interface Streams</b><br>
 * <b>{@link com.malhartech.stream.SocketInputStream}</b>: Implements {@link com.malhartech.engine.Stream} interface and provides
 * basic stream connection for a node to read from a socket. Users can use this class if they want to directly connect to
 * a outside socket<br>
 * <b>{@link com.malhartech.stream.SocketOutputStream}</b>: Implements {@link com.malhartech.engine.Stream} interface and provides
 * basic stream connection for a node to write to a socket. Most likely users would not use it to write to a socket by themselves.
 *   Would be used in adapters and via {@link com.malhartech.stream.BufferServerOutputStream}<br>
 * <br>
 *
 */

package com.malhartech.stream;

