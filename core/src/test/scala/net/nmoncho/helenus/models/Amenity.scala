/*
 * Copyright 2021 the original author or authors
 *
 * SPDX-License-Identifier: MIT
 */

package net.nmoncho.helenus.models

import net.nmoncho.helenus.api.RowMapper

final case class Amenity(name: String, description: String) derives RowMapper
