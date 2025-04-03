/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.models

import net.nmoncho.helenus.api.ColumnNamingScheme
import net.nmoncho.helenus.api.SnakeCase
import net.nmoncho.helenus.api.`type`.codec.Codec
import net.nmoncho.helenus.api.`type`.codec.UDTCodec

given ColumnNamingScheme = SnakeCase

final case class Address(
    street: String,
    city: String,
    stateOrProvince: String,
    postalCode: String,
    country: String
) derives UDTCodec

object Address:
    import net.nmoncho.helenus.*

    final val Empty: Address = Address("", "", "", "", "")
end Address
