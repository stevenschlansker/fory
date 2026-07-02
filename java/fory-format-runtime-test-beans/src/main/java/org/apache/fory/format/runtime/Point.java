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

import java.util.Objects;

/** A plain bean used to exercise generated row codecs from the runtime-only classpath. */
public class Point {
  private int count;
  private String label;

  public Point() {}

  public Point(int count, String label) {
    this.count = count;
    this.label = label;
  }

  public int getCount() {
    return count;
  }

  public void setCount(int count) {
    this.count = count;
  }

  public String getLabel() {
    return label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Point)) {
      return false;
    }
    Point point = (Point) o;
    return count == point.count && Objects.equals(label, point.label);
  }

  @Override
  public int hashCode() {
    return Objects.hash(count, label);
  }

  @Override
  public String toString() {
    return "Point{count=" + count + ", label='" + label + "'}";
  }
}
