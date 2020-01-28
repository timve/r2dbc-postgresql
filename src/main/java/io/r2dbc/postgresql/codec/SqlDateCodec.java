/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.r2dbc.postgresql.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.r2dbc.postgresql.client.Parameter;
import io.r2dbc.postgresql.message.Format;
import io.r2dbc.postgresql.type.PostgresqlObjectId;
import io.r2dbc.postgresql.util.Assert;
import reactor.util.annotation.Nullable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.Date;

public final class SqlDateCodec extends AbstractCodec<Date> {

    private final LocalDateCodec delegate;

    SqlDateCodec(ByteBufAllocator byteBufAllocator) {
        super(Date.class);

        Assert.requireNonNull(byteBufAllocator, "byteBufAllocator must not be null");
        this.delegate = new LocalDateCodec(byteBufAllocator);
    }

    @Override
    public Parameter encodeNull() {
        return this.delegate.encodeNull();
    }

    @Override
    boolean doCanDecode(PostgresqlObjectId type, Format format) {
        Assert.requireNonNull(format, "format must not be null");
        Assert.requireNonNull(type, "type must not be null");

        return this.delegate.doCanDecode(type, format);
    }

    @Override
    Date doDecode(ByteBuf buffer, PostgresqlObjectId dataType, @Nullable Format format, @Nullable Class<? extends Date> type) {
        Assert.requireNonNull(buffer, "byteBuf must not be null");

        LocalDate intermediary = this.delegate.doDecode(buffer, dataType, format, LocalDate.class);
        return new Date(intermediary.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    @Override
    Parameter doEncode(Date value) {
        Assert.requireNonNull(value, "value must not be null");

        return this.delegate.doEncode(value.toLocalDate());
    }

}
