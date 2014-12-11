/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mobileum.presto.metadata;

import com.facebook.presto.spi.type.Type;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;

public final class MetadataColumn {
	private final String name;
	private final Type type;

	@JsonCreator
	public MetadataColumn(@JsonProperty("name") String name,
			@JsonProperty("type") Type type) {
		checkArgument(!isNullOrEmpty(name), "name is null or is empty");
		this.name = name;
		this.type = checkNotNull(type, "type is null");
	}

	@JsonProperty
	public String getName() {
		return name;
	}

	@JsonProperty
	public Type getType() {
		return type;
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(name, type);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}

		MetadataColumn other = (MetadataColumn) obj;
		return Objects.equal(this.name, other.name)
				&& Objects.equal(this.type, other.type);
	}

	@Override
	public String toString() {
		return name + ":" + type;
	}
}
