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
 * Schema-evolution codec for the current {@link Account} layout, covering the row, array, and map
 * shapes. The processor precompiles the current codecs and projection codecs for the version-1
 * layout; at runtime the factory resolves both with only fory-format-runtime on the classpath,
 * dispatching a decode to the projection whose schema hash matches the payload.
 */
@ForyGenerate(evolution = true)
public interface AccountCodecs {
  AccountCodecs INSTANCE = RowCodecs.factory(AccountCodecs.class);

  byte[] encode(Account account);

  Account decode(byte[] bytes);

  void appendList(MemoryBuffer buffer, List<Account> accounts);

  List<Account> readList(MemoryBuffer buffer);

  void appendMap(MemoryBuffer buffer, Map<String, Account> accounts);

  Map<String, Account> readMap(MemoryBuffer buffer);
}
