/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.models

import net.nmoncho.helenus.api.RowMapper

final case class Hotel(id: String, name: String, phone: String, address: Address, pois: Set[String]) derives RowMapper

object Hotel:

    def byPoi(id: String, name: String, phone: String, address: Address): Hotel =
        Hotel(id, name, phone, address, Set())

end Hotel
