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

package org.apache.texera.amber.operator

/** Python definitions shared by several operators' standalone code, emitted
  * once per script via [[StandaloneCodeGenerator.standaloneHelpers]].
  */
object StandaloneHelpers {

  /**
    * Row equality as the set operators' `HashSet[Tuple]` decides it, for Intersect, Difference
    * and Symmetric Difference: NaN equals nothing and 0.0 differs from -0.0, where pandas counts
    * both pairs as equal.
    *
    *  - `_texera_set_row_keys`: one key per row, equal exactly where the engine's rows are.
    *  - `_texera_set_select`: the first row of each key, among those `keep` accepts.
    */
  val SetRowKeys: String =
    """def _texera_set_row_keys(df):
      |    import math
      |
      |    def key(v, missing):
      |        if missing:
      |            return None
      |        if isinstance(v, float):
      |            if math.isnan(v):
      |                return object()
      |            return (v, math.copysign(1.0, v))
      |        return v
      |
      |    return [
      |        tuple(key(v, m) for v, m in zip(values, missing))
      |        for values, missing in zip(
      |            df.itertuples(index=False, name=None), df.isna().to_numpy()
      |        )
      |    ]
      |
      |
      |def _texera_set_select(df, keep):
      |    seen = set()
      |    picked = []
      |    for i, key in enumerate(_texera_set_row_keys(df)):
      |        if key not in seen and keep(key):
      |            picked.append(i)
      |        seen.add(key)
      |    return df.iloc[picked].reset_index(drop=True)""".stripMargin

  /**
    * `AttributeTypeUtils` in Python, so a cast gives the engine's answer.
    *
    *  - `_texera_cast_boolean`: "true"/"false" in any case, else a number where only 1 is true.
    *  - `_texera_parse_number`: Java's NumberFormat, stopping at a non-digit and dropping commas.
    *  - `_texera_saturate`: a double narrowed as Java does, NaN to 0 and the rest to the bounds.
    *  - `_texera_long_value`: a double clamped to long's range.
    *  - `_texera_cast_integral`: the LONG cast.
    *  - `_texera_cast_int32`: the INTEGER cast, where a long wraps and a double clamps.
    *  - `_texera_text_to_timestamp`: each cell parsed alone, any year; unreadable text raises.
    *  - `_texera_epoch_millis_to_timestamp`: epoch milliseconds shown in the machine's zone.
    *  - `_texera_local_zone`: the machine's zone by its IANA name.
    *  - `_texera_standard_offset`: the zone's standard offset, which Java uses before 1900.
    *  - `_texera_local_wall_clock(s)`: the machine's wall clock at an instant, per value or column.
    *  - `_texera_wall_clock_instants`: getTime of each wall clock, a gap or an overlap read at
    *    the standard offset as java.util.Date reads it.
    *  - `_texera_cast_double(s)`: the DOUBLE cast, keeping NaN apart from null.
    *  - `_texera_java_text`: a value as Java's toString writes it.
    *  - `_texera_cast_string`: a column as text, decided by the column's type.
    *  - `_texera_cast_cells`: a cast applied cell by cell, reading nulls from the column's mask.
    *  - `_texera_java_trim`: `String.trim`, dropping only the characters up to U+0020.
    *  - `_texera_parse_int`: `Integer.parseInt` or `Long.parseLong`, a sign and digits in range.
    *  - `_texera_java_double`: `Double.parseDouble`, with a d/f suffix and hex, refusing the rest.
    *  - `_texera_double_key`: `Double.compareTo`'s order, -0.0 below 0.0 and NaN above all.
    *  - `_texera_read_doubles`: a DOUBLE column pandas left as text, read the engine's way.
    */
  val AttributeCasts: String =
    """def _texera_cast_boolean(x):
      |    if isinstance(x, str):
      |        text = x.strip()
      |        lowered = text.lower()
      |        if lowered == "true":
      |            return True
      |        if lowered == "false":
      |            return False
      |        return int(text) == 1
      |    return x != 0
      |
      |
      |def _texera_parse_number(text):
      |    import re
      |
      |    match = re.match(r"\s*(-?)([0-9,]*)(?:\.([0-9]*))?", text)
      |    sign = -1 if match.group(1) == "-" else 1
      |    digits = (match.group(2) or "").replace(",", "")
      |    fraction = match.group(3) or ""
      |    if not digits and not fraction:
      |        raise ValueError("Unparseable number: " + repr(text))
      |    if fraction:
      |        return sign * float((digits or "0") + "." + fraction), True
      |    value = sign * int(digits)
      |    if -(2 ** 63) <= value <= 2 ** 63 - 1:
      |        return value, False
      |    return float(value), True
      |
      |
      |def _texera_saturate(value, low, high):
      |    if value != value:
      |        return 0
      |    if value <= low:
      |        return low
      |    if value >= high:
      |        return high
      |    return int(value)
      |
      |
      |def _texera_long_value(value, is_double):
      |    if is_double:
      |        return _texera_saturate(value, -(2 ** 63), 2 ** 63 - 1)
      |    return value
      |
      |
      |def _texera_cast_integral(x):
      |    if isinstance(x, str):
      |        return _texera_long_value(*_texera_parse_number(x))
      |    if isinstance(x, bool):
      |        return 1 if x else 0
      |    if isinstance(x, float):
      |        return _texera_long_value(x, True)
      |    return int(x)
      |
      |
      |def _texera_cast_int32(x, wrap):
      |    if isinstance(x, str):
      |        value, is_double = _texera_parse_number(x)
      |        wrap = not is_double
      |    elif isinstance(x, bool):
      |        value = 1 if x else 0
      |    else:
      |        value = x
      |    if wrap:
      |        return ((int(value) + 2147483648) % 4294967296) - 2147483648
      |    return _texera_saturate(value, -2147483648, 2147483647)
      |
      |
      |def _texera_text_to_timestamp(s):
      |    from dateutil.parser import parse as _parse_date
      |
      |    if pd.api.types.is_datetime64_any_dtype(s):
      |        if getattr(s.dtype, "tz", None) is not None:
      |            return _texera_local_wall_clocks(s)
      |        return s
      |
      |    zone = _texera_local_zone()
      |
      |    def _one(x):
      |        if pd.isna(x):
      |            return None
      |        try:
      |            parsed = _parse_date(str(x).strip())
      |        except (ValueError, OverflowError) as e:
      |            raise ValueError(f"Failed to parse type str to Timestamp: {x}") from e
      |        if parsed.tzinfo is not None:
      |            parsed = _texera_local_wall_clock(parsed, zone)
      |        return parsed.replace(microsecond=parsed.microsecond // 1000 * 1000)
      |
      |    return s.map(_one).astype("datetime64[us]")
      |
      |
      |def _texera_epoch_millis_to_timestamp(s):
      |    return _texera_local_wall_clocks(pd.to_datetime(s, unit="ms", errors="coerce", utc=True))
      |
      |
      |def _texera_local_zone():
      |    import os
      |    import zoneinfo
      |
      |    names = [os.environ.get("TZ", "").lstrip(":")]
      |    try:
      |        import tzlocal
      |
      |        names.insert(0, str(tzlocal.get_localzone()))
      |    except Exception:
      |        pass
      |    link = os.path.realpath("/etc/localtime")
      |    if "zoneinfo/" in link:
      |        names.append(link.split("zoneinfo/", 1)[1])
      |    for name in names:
      |        try:
      |            return zoneinfo.ZoneInfo(name)
      |        except (ValueError, KeyError):
      |            continue
      |    return None
      |
      |
      |def _texera_standard_offset(zone):
      |    import datetime
      |    import time
      |
      |    if zone is None:
      |        return datetime.timedelta(seconds=-time.timezone)
      |    now = datetime.datetime.now(zone)
      |    return now.utcoffset() - now.dst()
      |
      |
      |def _texera_local_wall_clock(moment, zone):
      |    import datetime
      |
      |    from dateutil.tz import tzlocal
      |
      |    utc = moment.astimezone(datetime.timezone.utc).replace(tzinfo=None)
      |    if utc < datetime.datetime(1900, 1, 1):
      |        return utc + _texera_standard_offset(zone)
      |    if zone is not None:
      |        return moment.astimezone(zone).replace(tzinfo=None)
      |    try:
      |        return moment.astimezone(tzlocal()).replace(tzinfo=None)
      |    except (OSError, OverflowError):
      |        return utc + _texera_standard_offset(zone)
      |
      |
      |def _texera_local_wall_clocks(s):
      |    import datetime
      |
      |    from dateutil.tz import tzlocal
      |
      |    zone = _texera_local_zone()
      |    utc = s.dt.tz_convert("UTC").dt.tz_localize(None)
      |    if zone is not None:
      |        wall = s.dt.tz_convert(zone).dt.tz_localize(None)
      |        before = utc < pd.Timestamp(1900, 1, 1)
      |        return wall.where(~before, utc + _texera_standard_offset(zone))
      |    try:
      |        return s.dt.tz_convert(tzlocal()).dt.tz_localize(None)
      |    except (OSError, OverflowError):
      |        pass
      |
      |    def _offset(t):
      |        if pd.isna(t):
      |            return 0
      |        moment = t.to_pydatetime(warn=False)
      |        utc = moment.astimezone(datetime.timezone.utc).replace(tzinfo=None)
      |        return int((_texera_local_wall_clock(moment, None) - utc).total_seconds())
      |
      |    seconds = pd.Series([_offset(t) for t in s], index=s.index)
      |    return utc + pd.to_timedelta(seconds, unit="s").astype("timedelta64[s]")
      |
      |
      |def _texera_wall_clock_instants(s):
      |    from dateutil.tz import tzlocal
      |
      |    walls = s.dt.floor("ms").astype("datetime64[ms]")
      |    zone = _texera_local_zone()
      |    standard = walls - _texera_standard_offset(zone)
      |    before = standard < pd.Timestamp(1900, 1, 1)
      |
      |    def localized(t):
      |        return (
      |            t.dt.tz_localize(zone or tzlocal(), ambiguous=False, nonexistent=pd.Timedelta("1h"))
      |            .dt.tz_convert("UTC")
      |            .dt.tz_localize(None)
      |        )
      |
      |    try:
      |        return localized(walls).where(~before, standard)
      |    except (OSError, OverflowError):
      |        pass
      |
      |    def one(i):
      |        if before.iloc[i]:
      |            return standard.iloc[i]
      |        try:
      |            return localized(walls.iloc[i : i + 1]).iloc[0]
      |        except (OSError, OverflowError):
      |            return standard.iloc[i]
      |
      |    return pd.Series([one(i) for i in range(len(walls))], index=s.index, dtype="datetime64[ms]")
      |
      |
      |def _texera_cast_double(x):
      |    if isinstance(x, str):
      |        return float(x.strip())
      |    return float(x)
      |
      |
      |def _texera_cast_doubles(s):
      |    holes = s.isna().to_numpy()
      |    values = _texera_cast_cells(s, _texera_cast_double, 0.0).to_numpy(dtype="float64")
      |    return pd.Series(pd.arrays.FloatingArray(values, holes), index=s.index, name=s.name)
      |
      |
      |def _texera_java_text(x):
      |    import datetime
      |    import decimal
      |
      |    if pd.api.types.is_bool(x):
      |        return "true" if x else "false"
      |    if isinstance(x, datetime.datetime):
      |        nanos = x.microsecond * 1000 + getattr(x, "nanosecond", 0)
      |        return x.strftime("%Y-%m-%d %H:%M:%S") + "." + (f"{nanos:09d}".rstrip("0") or "0")
      |    if pd.api.types.is_integer(x):
      |        return str(int(x))
      |    if pd.api.types.is_float(x):
      |        value = float(x)
      |        if value != value:
      |            return "NaN"
      |        if value in (float("inf"), float("-inf")):
      |            return "Infinity" if value > 0 else "-Infinity"
      |        if value == 0 or 1e-3 <= abs(value) < 1e7:
      |            return repr(value)
      |        sign, digits, exponent = decimal.Decimal(repr(value)).as_tuple()
      |        power = len(digits) + exponent - 1
      |        kept = "".join(map(str, digits)).rstrip("0") or "0"
      |        return ("-" if sign else "") + kept[0] + "." + (kept[1:] or "0") + "E" + str(power)
      |    return str(x)
      |
      |
      |def _texera_java_trim(text):
      |    return text.strip("".join(map(chr, range(33))))
      |
      |
      |def _texera_parse_int(text, bits):
      |    import re
      |
      |    text = _texera_java_trim(text)
      |    bound = 2 ** (bits - 1)
      |    if not re.fullmatch(r"[+-]?\d+", text) or not -bound <= int(text) < bound:
      |        raise ValueError("For input string: " + repr(text))
      |    return int(text)
      |
      |
      |def _texera_java_double(text):
      |    import re
      |
      |    text = _texera_java_trim(text)
      |    if not re.fullmatch(
      |        r"[+-]?(NaN|Infinity|((\d+\.?\d*|\.\d+)([eE][+-]?\d+)?"
      |        r"|0[xX]([0-9a-fA-F]+\.?|[0-9a-fA-F]*\.[0-9a-fA-F]+)[pP][+-]?\d+)[fFdD]?)",
      |        text,
      |        re.ASCII,
      |    ):
      |        raise ValueError("For input string: " + repr(text))
      |    if text[-1] in "dDfF":
      |        text = text[:-1]
      |    if "x" not in text.lower():
      |        return float(text)
      |    try:
      |        return float.fromhex(text)
      |    except OverflowError:
      |        return float("-inf") if text.startswith("-") else float("inf")
      |
      |
      |def _texera_double_key(x):
      |    import math
      |
      |    if math.isnan(x):
      |        return (1, 0.0, 0.0)
      |    return (0, x, math.copysign(1.0, x))
      |
      |
      |def _texera_read_doubles(s):
      |    import numpy as np
      |
      |    if pd.api.types.is_numeric_dtype(s):
      |        return s
      |    holes = s.isna().to_numpy()
      |    values = np.array(
      |        [np.nan if hole else _texera_java_double(x) for x, hole in zip(s, holes)],
      |        dtype="float64",
      |    )
      |    if np.isnan(values[~holes]).any():
      |        values = pd.arrays.FloatingArray(values, holes)
      |    return pd.Series(values, index=s.index, name=s.name)
      |
      |
      |def _texera_cast_string(s):
      |    if pd.api.types.is_bool_dtype(s):
      |        return s.map(lambda x: None if pd.isna(x) else ("true" if x else "false"))
      |    if pd.api.types.is_integer_dtype(s):
      |        return s.map(lambda x: None if pd.isna(x) else str(int(x)))
      |    return _texera_cast_cells(s, _texera_java_text, None)
      |
      |
      |def _texera_cast_cells(s, cast, hole):
      |    holes = s.isna().to_numpy()
      |    return pd.Series(
      |        [hole if empty else cast(x) for x, empty in zip(s, holes)],
      |        index=s.index,
      |        name=s.name,
      |        dtype=object,
      |    )""".stripMargin
}
