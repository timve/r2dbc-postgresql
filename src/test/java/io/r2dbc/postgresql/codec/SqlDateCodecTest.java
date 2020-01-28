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

import io.r2dbc.postgresql.client.Parameter;
import io.r2dbc.postgresql.client.ParameterAssert;
import org.junit.jupiter.api.Test;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static io.r2dbc.postgresql.client.Parameter.NULL_VALUE;
import static io.r2dbc.postgresql.message.Format.FORMAT_BINARY;
import static io.r2dbc.postgresql.message.Format.FORMAT_TEXT;
import static io.r2dbc.postgresql.type.PostgresqlObjectId.*;
import static io.r2dbc.postgresql.type.PostgresqlObjectId.TIMESTAMP;
import static io.r2dbc.postgresql.util.ByteBufUtils.encode;
import static io.r2dbc.postgresql.util.TestByteBufAllocator.TEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SqlDateCodecTest {

    private static final int dataType = TIMESTAMP.getObjectId();

    @Test
    void constructorNoByteBufAllocator() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SqlDateCodec(null))
                .withMessage("byteBufAllocator must not be null");
    }

    @Test
    void decode() {
        Instant testInstant = LocalDate.parse("2010-02-01").atStartOfDay().atZone(ZoneId.systemDefault()).toInstant();
        Date date = new Date(testInstant.toEpochMilli());

        assertThat(new SqlDateCodec(TEST).decode(encode(TEST, "2010-02-01 10:08:04.412"), dataType, FORMAT_TEXT, Date.class))
                .isEqualTo(date);
    }

    @Test
    void decodeFromDate() {
        Date date = new Date(LocalDateTime.parse("2018-11-04T00:00:00.000").atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

        assertThat(new SqlDateCodec(TEST).decode(encode(TEST, "2018-11-04"), DATE.getObjectId(), FORMAT_TEXT, Date.class))
                .isEqualTo(date);
    }

    @Test
    void decodeNoByteBuf() {
        assertThat(new SqlDateCodec(TEST).decode(null, dataType, FORMAT_TEXT, Date.class)).isNull();
    }

    @Test
    void doCanDecode() {
        SqlDateCodec codec = new SqlDateCodec(TEST);

        assertThat(codec.doCanDecode(TIMESTAMP, FORMAT_BINARY)).isTrue();
        assertThat(codec.doCanDecode(MONEY, FORMAT_TEXT)).isFalse();
        assertThat(codec.doCanDecode(TIMESTAMP, FORMAT_TEXT)).isTrue();
    }

    @Test
    void doCanDecodeNoFormat() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SqlDateCodec(TEST).doCanDecode(VARCHAR, null))
                .withMessage("format must not be null");
    }

    @Test
    void doCanDecodeNoType() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SqlDateCodec(TEST).doCanDecode(null, FORMAT_TEXT))
                .withMessage("type must not be null");
    }

    @Test
    void doEncode() {
        Instant testInstant = LocalDate.parse("2010-02-01").atStartOfDay().atZone(ZoneId.systemDefault()).toInstant();
        Date date = new Date(testInstant.toEpochMilli());

        ParameterAssert.assertThat(new SqlDateCodec(TEST).doEncode(date))
                .hasFormat(FORMAT_TEXT)
                .hasType(DATE.getObjectId())
                .hasValue(encode(TEST, "2010-02-01"));
    }

    @Test
    void doEncodeNoValue() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SqlDateCodec(TEST).doEncode(null))
                .withMessage("value must not be null");
    }

    @Test
    void encodeNull() {
        ParameterAssert.assertThat(new SqlDateCodec(TEST).encodeNull())
                .isEqualTo(new Parameter(FORMAT_TEXT, DATE.getObjectId(), NULL_VALUE));
    }

}