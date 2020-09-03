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

import io.netty.util.collection.IntCollections;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

class Http2WebSocketUtils {

  static final class SingleElementOptimizedMap<T> implements IntObjectMap<T> {
    /* 0: empty
     * -1: delegated */
    int singleKey;
    T singleValue;
    IntObjectMap<T> delegate = IntCollections.emptyMap();

    @Override
    public T get(int key) {
      int sk = singleKey;
      if (key == sk) {
        return singleValue;
      }
      if (sk == -1) {
        return delegate.get(key);
      }
      return null;
    }

    @Override
    public T put(int key, T value) {
      int sk = singleKey;
      /*empty or replace*/
      if (sk == 0 || key == sk) {
        T sv = singleValue;
        singleKey = key;
        singleValue = value;
        return sv;
      }
      /*put while nonEmpty - delegate*/
      IntObjectMap<T> d = delegate;
      if (d.isEmpty()) {
        d = delegate = new IntObjectHashMap<>(4);
        d.put(sk, singleValue);
        singleKey = -1;
        singleValue = null;
      }
      return d.put(key, value);
    }

    @Override
    public T remove(int key) {
      int sk = singleKey;
      if (key == sk) {
        T sv = singleValue;
        singleKey = 0;
        singleValue = null;
        return sv;
      }
      /*delegated, so not empty*/
      if (sk == -1) {
        IntObjectMap<T> d = delegate;
        T removed = d.remove(key);
        if (d.isEmpty()) {
          singleKey = 0;
          delegate = IntCollections.emptyMap();
        }
        return removed;
      }
      /*either single key does not match, or empty*/
      return null;
    }

    @Override
    public boolean containsKey(int key) {
      int sk = singleKey;
      return sk == key || sk == -1 && delegate.containsKey(key);
    }

    @Override
    public int size() {
      int sk = singleKey;
      switch (sk) {
        case 0:
          return 0;
        case -1:
          return delegate.size();
          /*sk > 0*/
        default:
          return 1;
      }
    }

    @Override
    public boolean isEmpty() {
      return singleKey == 0;
    }

    @Override
    public void clear() {
      singleKey = 0;
      singleValue = null;
      delegate = IntCollections.emptyMap();
    }

    @Override
    public void forEach(BiConsumer<? super Integer, ? super T> action) {
      int sk = singleKey;
      if (sk > 0) {
        action.accept(sk, singleValue);
      } else if (sk == -1) {
        delegate.forEach(action);
      }
    }

    @Override
    public Iterable<PrimitiveEntry<T>> entries() {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean containsKey(Object key) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean containsValue(Object value) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public T get(Object key) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public T put(Integer key, T value) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public T remove(Object key) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void putAll(Map<? extends Integer, ? extends T> m) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Set<Integer> keySet() {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Collection<T> values() {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public Set<Entry<Integer, T>> entrySet() {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public boolean equals(Object o) {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public int hashCode() {
      throw new UnsupportedOperationException("Not implemented");
    }
  }
}
