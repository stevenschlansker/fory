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

package org.apache.fory.format.runtime;

import java.util.List;
import java.util.Map;
import org.apache.fory.format.annotation.ForyGenerate;
import org.apache.fory.format.encoder.RowCodecs;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Producer-side codec that writes the version-1 account layout, so {@link AccountCodecs} can decode
 * an older payload through its schema-evolution projection path. Evolution-aware so the payload
 * carries the strict schema hash the reader dispatches on, matching a real older peer.
 */
@ForyGenerate(evolution = true)
public interface AccountV1Codecs {
  AccountV1Codecs INSTANCE = RowCodecs.factory(AccountV1Codecs.class);

  byte[] encode(AccountV1 account);

  void appendList(MemoryBuffer buffer, List<AccountV1> accounts);

  void appendMap(MemoryBuffer buffer, Map<String, AccountV1> accounts);
}
