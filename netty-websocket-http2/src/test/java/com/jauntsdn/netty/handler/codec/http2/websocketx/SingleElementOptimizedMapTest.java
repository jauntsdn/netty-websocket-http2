/*
 * Copyright 2020 - present Maksym Ostroverkhov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jauntsdn.netty.handler.codec.http2.websocketx;

import com.jauntsdn.netty.handler.codec.http2.websocketx.Http2WebSocketChannelHandler.SingleElementOptimizedMap;
import io.netty.util.collection.IntCollections;
import io.netty.util.collection.IntObjectHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class SingleElementOptimizedMapTest {

  @Test
  void putSingleElement() {
    int expectedKey = 42;
    String expectedValue = "first";

    SingleElementOptimizedMap<String> map = new SingleElementOptimizedMap<>();
    map.put(expectedKey, expectedValue);

    Assertions.assertThat(map.get(expectedKey)).isEqualTo(expectedValue);
    Assertions.assertThat(map.size()).isEqualTo(1);
    Assertions.assertThat(map.isEmpty()).isFalse();
    Assertions.assertThat(map.containsKey(expectedKey)).isTrue();

    List<Integer> forEachKeys = new ArrayList<>();
    List<String> forEachValues = new ArrayList<>();
    map.forEach(
        (k, v) -> {
          forEachKeys.add(k);
          forEachValues.add(v);
        });
    Assertions.assertThat(forEachKeys).isEqualTo(Collections.singletonList(expectedKey));
    Assertions.assertThat(forEachValues).isEqualTo(Collections.singletonList(expectedValue));

    Assertions.assertThat(map.singleKey).isEqualTo(expectedKey);
    Assertions.assertThat(map.singleValue).isEqualTo(expectedValue);
    Assertions.assertThat(map.delegate).isSameAs(IntCollections.emptyMap());
  }

  @Test
  void removeWhileEmpty() {
    int key = 42;

    SingleElementOptimizedMap<String> map = new SingleElementOptimizedMap<>();
    String removed = map.remove(key);

    Assertions.assertThat(removed).isNull();
    Assertions.assertThat(map.size()).isEqualTo(0);
    Assertions.assertThat(map.isEmpty()).isTrue();

    Assertions.assertThat(map.singleKey).isEqualTo(0);
    Assertions.assertThat(map.singleValue).isNull();
    Assertions.assertThat(map.delegate).isSameAs(IntCollections.emptyMap());
  }

  @SuppressWarnings({"ConstantConditions", "RedundantOperationOnEmptyContainer"})
  @Test
  void removePresentSingleElement() {
    int expectedKey = 42;
    String expectedValue = "first";

    SingleElementOptimizedMap<String> map = new SingleElementOptimizedMap<>();
    map.put(expectedKey, expectedValue);
    String removed = map.remove(expectedKey);

    Assertions.assertThat(removed).isEqualTo(expectedValue);
    Assertions.assertThat(map.get(expectedKey)).isNull();
    Assertions.assertThat(map.size()).isEqualTo(0);
    Assertions.assertThat(map.isEmpty()).isTrue();
    Assertions.assertThat(map.containsKey(expectedKey)).isFalse();

    List<Integer> forEachKeys = new ArrayList<>();
    List<String> forEachValues = new ArrayList<>();
    map.forEach(
        (k, v) -> {
          forEachKeys.add(k);
          forEachValues.add(v);
        });
    Assertions.assertThat(forEachKeys).isEmpty();
    Assertions.assertThat(forEachValues).isEmpty();

    Assertions.assertThat(map.singleKey).isEqualTo(0);
    Assertions.assertThat(map.singleValue).isNull();
    Assertions.assertThat(map.delegate).isSameAs(IntCollections.emptyMap());
  }

  @Test
  void removeAbsentSingleElement() {
    int expectedKey = 42;
    String expectedValue = "first";

    SingleElementOptimizedMap<String> map = new SingleElementOptimizedMap<>();
    map.put(expectedKey, expectedValue);
    String removed = map.remove(0);

    Assertions.assertThat(removed).isNull();
    Assertions.assertThat(map.get(expectedKey)).isEqualTo(expectedValue);
    Assertions.assertThat(map.size()).isEqualTo(1);
    Assertions.assertThat(map.isEmpty()).isFalse();
    Assertions.assertThat(map.containsKey(expectedKey)).isTrue();

    List<Integer> forEachKeys = new ArrayList<>();
    List<String> forEachValues = new ArrayList<>();
    map.forEach(
        (k, v) -> {
          forEachKeys.add(k);
          forEachValues.add(v);
        });
    Assertions.assertThat(forEachKeys).isEqualTo(Collections.singletonList(expectedKey));
    Assertions.assertThat(forEachValues).isEqualTo(Collections.singletonList(expectedValue));

    Assertions.assertThat(map.singleKey).isEqualTo(expectedKey);
    Assertions.assertThat(map.singleValue).isEqualTo(expectedValue);
    Assertions.assertThat(map.delegate).isSameAs(IntCollections.emptyMap());
  }

  @Test
  void removeThenPutSingleElement() {
    int expectedKey = 42;
    String expectedValue = "first";

    SingleElementOptimizedMap<String> map = new SingleElementOptimizedMap<>();
    map.put(expectedKey, expectedValue);
    map.remove(expectedKey);
    String prevValue = map.put(expectedKey, expectedValue);

    Assertions.assertThat(prevValue).isNull();
    Assertions.assertThat(map.get(expectedKey)).isEqualTo(expectedValue);
    Assertions.assertThat(map.size()).isEqualTo(1);
    Assertions.assertThat(map.isEmpty()).isFalse();
    Assertions.assertThat(map.containsKey(expectedKey)).isTrue();

    List<Integer> forEachKeys = new ArrayList<>();
    List<String> forEachValues = new ArrayList<>();
    map.forEach(
        (k, v) -> {
          forEachKeys.add(k);
          forEachValues.add(v);
        });
    Assertions.assertThat(forEachKeys).isEqualTo(Collections.singletonList(expectedKey));
    Assertions.assertThat(forEachValues).isEqualTo(Collections.singletonList(expectedValue));

    Assertions.assertThat(map.singleKey).isEqualTo(expectedKey);
    Assertions.assertThat(map.singleValue).isEqualTo(expectedValue);
    Assertions.assertThat(map.delegate).isSameAs(IntCollections.emptyMap());
  }

  @Test
  void replaceSingleElement() {
    int expectedKey = 42;
    String originalValue = "first";
    String replaceValue = "second";

    SingleElementOptimizedMap<String> map = new SingleElementOptimizedMap<>();
    map.put(expectedKey, originalValue);
    String replaced = map.put(expectedKey, replaceValue);

    Assertions.assertThat(replaced).isEqualTo(originalValue);
    Assertions.assertThat(map.get(expectedKey)).isEqualTo(replaceValue);
    Assertions.assertThat(map.size()).isEqualTo(1);
    Assertions.assertThat(map.isEmpty()).isFalse();
    Assertions.assertThat(map.containsKey(expectedKey)).isTrue();

    List<Integer> forEachKeys = new ArrayList<>();
    List<String> forEachValues = new ArrayList<>();
    map.forEach(
        (k, v) -> {
          forEachKeys.add(k);
          forEachValues.add(v);
        });
    Assertions.assertThat(forEachKeys).isEqualTo(Collections.singletonList(expectedKey));
    Assertions.assertThat(forEachValues).isEqualTo(Collections.singletonList(replaceValue));

    Assertions.assertThat(map.singleKey).isEqualTo(expectedKey);
    Assertions.assertThat(map.singleValue).isEqualTo(replaceValue);
    Assertions.assertThat(map.delegate).isSameAs(IntCollections.emptyMap());
  }

  @Test
  void putMultipleElements() {
    int firstKey = 42;
    String firstValue = "first";
    int secondKey = 7;
    String secondValue = "second";

    SingleElementOptimizedMap<String> map = new SingleElementOptimizedMap<>();
    map.put(firstKey, firstValue);
    map.put(secondKey, secondValue);

    Assertions.assertThat(map.get(firstKey)).isEqualTo(firstValue);
    Assertions.assertThat(map.get(secondKey)).isEqualTo(secondValue);
    Assertions.assertThat(map.size()).isEqualTo(2);
    Assertions.assertThat(map.isEmpty()).isFalse();
    Assertions.assertThat(map.containsKey(firstKey)).isTrue();
    Assertions.assertThat(map.containsKey(secondKey)).isTrue();

    List<Integer> forEachKeys = new ArrayList<>();
    List<String> forEachValues = new ArrayList<>();
    map.forEach(
        (k, v) -> {
          forEachKeys.add(k);
          forEachValues.add(v);
        });
    Assertions.assertThat(forEachKeys).isEqualTo(Arrays.asList(firstKey, secondKey));
    Assertions.assertThat(forEachValues).isEqualTo(Arrays.asList(firstValue, secondValue));

    Assertions.assertThat(map.singleKey).isEqualTo(-1);
    Assertions.assertThat(map.singleValue).isEqualTo(null);
    Assertions.assertThat(map.delegate).isExactlyInstanceOf(IntObjectHashMap.class);
    Assertions.assertThat(map.delegate).hasSize(2);
  }

  @Test
  void removeSingleAfterPutMultipleElements() {
    int firstKey = 42;
    String firstValue = "first";
    int secondKey = 7;
    String secondValue = "second";

    SingleElementOptimizedMap<String> map = new SingleElementOptimizedMap<>();
    map.put(firstKey, firstValue);
    map.put(secondKey, secondValue);
    String removed = map.remove(firstKey);

    Assertions.assertThat(removed).isEqualTo(firstValue);
    Assertions.assertThat(map.get(firstKey)).isNull();
    Assertions.assertThat(map.get(secondKey)).isEqualTo(secondValue);
    Assertions.assertThat(map.size()).isEqualTo(1);
    Assertions.assertThat(map.isEmpty()).isFalse();
    Assertions.assertThat(map.containsKey(firstKey)).isFalse();
    Assertions.assertThat(map.containsKey(secondKey)).isTrue();

    List<Integer> forEachKeys = new ArrayList<>();
    List<String> forEachValues = new ArrayList<>();
    map.forEach(
        (k, v) -> {
          forEachKeys.add(k);
          forEachValues.add(v);
        });
    Assertions.assertThat(forEachKeys).isEqualTo(Collections.singletonList(secondKey));
    Assertions.assertThat(forEachValues).isEqualTo(Collections.singletonList(secondValue));

    Assertions.assertThat(map.singleKey).isEqualTo(-1);
    Assertions.assertThat(map.singleValue).isEqualTo(null);
    Assertions.assertThat(map.delegate).isExactlyInstanceOf(IntObjectHashMap.class);
    Assertions.assertThat(map.delegate).hasSize(1);
  }

  @SuppressWarnings({"ConstantConditions", "RedundantOperationOnEmptyContainer"})
  @Test
  void removeAllAfterPutMultipleElements() {
    int firstKey = 42;
    String firstValue = "first";
    int secondKey = 7;
    String secondValue = "second";

    SingleElementOptimizedMap<String> map = new SingleElementOptimizedMap<>();
    map.put(firstKey, firstValue);
    map.put(secondKey, secondValue);
    String removedFirst = map.remove(firstKey);
    String removedSecond = map.remove(secondKey);

    Assertions.assertThat(removedFirst).isEqualTo(firstValue);
    Assertions.assertThat(removedSecond).isEqualTo(secondValue);

    Assertions.assertThat(map.get(firstKey)).isNull();
    Assertions.assertThat(map.get(secondKey)).isNull();
    Assertions.assertThat(map.size()).isEqualTo(0);
    Assertions.assertThat(map.isEmpty()).isTrue();
    Assertions.assertThat(map.containsKey(firstKey)).isFalse();
    Assertions.assertThat(map.containsKey(secondKey)).isFalse();

    List<Integer> forEachKeys = new ArrayList<>();
    List<String> forEachValues = new ArrayList<>();
    map.forEach(
        (k, v) -> {
          forEachKeys.add(k);
          forEachValues.add(v);
        });
    Assertions.assertThat(forEachKeys).hasSize(0);
    Assertions.assertThat(forEachValues).hasSize(0);

    Assertions.assertThat(map.singleKey).isEqualTo(0);
    Assertions.assertThat(map.singleValue).isEqualTo(null);
    Assertions.assertThat(map.delegate).isSameAs(IntCollections.emptyMap());
  }

  @SuppressWarnings({"ConstantConditions", "RedundantOperationOnEmptyContainer"})
  @Test
  void clear() {
    int firstKey = 42;
    String firstValue = "first";
    int secondKey = 7;
    String secondValue = "second";

    SingleElementOptimizedMap<String> map = new SingleElementOptimizedMap<>();
    map.put(firstKey, firstValue);
    map.put(secondKey, secondValue);
    map.clear();

    Assertions.assertThat(map.get(firstKey)).isNull();
    Assertions.assertThat(map.get(secondKey)).isNull();
    Assertions.assertThat(map.size()).isEqualTo(0);
    Assertions.assertThat(map.isEmpty()).isTrue();
    Assertions.assertThat(map.containsKey(firstKey)).isFalse();
    Assertions.assertThat(map.containsKey(secondKey)).isFalse();

    List<Integer> forEachKeys = new ArrayList<>();
    List<String> forEachValues = new ArrayList<>();
    map.forEach(
        (k, v) -> {
          forEachKeys.add(k);
          forEachValues.add(v);
        });
    Assertions.assertThat(forEachKeys).hasSize(0);
    Assertions.assertThat(forEachValues).hasSize(0);

    Assertions.assertThat(map.singleKey).isEqualTo(0);
    Assertions.assertThat(map.singleValue).isEqualTo(null);
    Assertions.assertThat(map.delegate).isSameAs(IntCollections.emptyMap());
  }
}
