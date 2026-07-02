/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.format.encoder;

import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.CompactBinaryArray;
import org.apache.fory.format.row.binary.CompactBinaryMap;
import org.apache.fory.format.row.binary.CompactRowLayout;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Runtime row-format encodings: the writer, row-factory, and container allocation for the default
 * and compact wire layouts. This is the single source of truth for the codegen-free half of the
 * format contract; the code-generation {@code CodecFormat} enums delegate their runtime methods
 * here and add the builder-producing methods.
 */
enum RowEncoding implements Encoding {
  DEFAULT {
    @Override
    public Schema sortSchema(final Schema schema) {
      return schema;
    }

    @Override
    public BaseBinaryRowWriter newWriter(final Schema schema) {
      return new BinaryRowWriter(schema);
    }

    @Override
    public BaseBinaryRowWriter newWriter(final Schema schema, final MemoryBuffer buffer) {
      return new BinaryRowWriter(schema, buffer);
    }

    @Override
    public BinaryArrayWriter newArrayWriter(final Field field) {
      return new BinaryArrayWriter(field);
    }

    @Override
    public BinaryArrayWriter newArrayWriter(final Field field, final MemoryBuffer buffer) {
      return new BinaryArrayWriter(field, buffer);
    }

    @Override
    public RowFactory newRowFactory(final Schema schema) {
      return () -> new BinaryRow(schema);
    }
  },
  COMPACT {
    @Override
    public Schema sortSchema(final Schema schema) {
      return CompactBinaryRowWriter.sortSchema(schema);
    }

    @Override
    public BaseBinaryRowWriter newWriter(final Schema schema) {
      return new CompactBinaryRowWriter(schema);
    }

    @Override
    public BaseBinaryRowWriter newWriter(final Schema schema, final MemoryBuffer buffer) {
      return new CompactBinaryRowWriter(schema, buffer);
    }

    @Override
    public BinaryArrayWriter newArrayWriter(final Field field) {
      return new CompactBinaryArrayWriter(field);
    }

    @Override
    public BinaryArrayWriter newArrayWriter(final Field field, final MemoryBuffer buffer) {
      return new CompactBinaryArrayWriter(field, buffer);
    }

    @Override
    public RowFactory newRowFactory(final Schema schema) {
      // Compute the compact layout once; every newRow() call reuses it (same model as the writer
      // and the nested-slot read path).
      final CompactRowLayout layout = new CompactRowLayout(schema);
      return layout::newRow;
    }
  };

  @Override
  public BinaryArray newArray(final Field field) {
    return this == COMPACT ? new CompactBinaryArray(field) : new BinaryArray(field);
  }

  @Override
  public BinaryMap newMap(final Field field) {
    return this == COMPACT ? new CompactBinaryMap(field) : new BinaryMap(field);
  }
}
