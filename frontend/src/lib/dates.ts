const formatters = new Map<string, Intl.DateTimeFormat>()

/** `en` is formatted as `en-IE`: the day before the month, as in Portugal and the design canvas. */
function formatter(language: string, options: Intl.DateTimeFormatOptions) {
  const locale = language === 'en' ? 'en-IE' : language
  const key = `${locale} ${JSON.stringify(options)}`
  let cached = formatters.get(key)
  if (cached === undefined) {
    cached = new Intl.DateTimeFormat(locale, options)
    formatters.set(key, cached)
  }
  return cached
}

/**
 * The day of `instant`, written out for `language`: "3 June 2026", "3 de junho de 2026".
 *
 * English puts the day first, as people in Portugal and Ireland write it and as the design canvas
 * does, rather than the US order ("June 3, 2026") that plain `en` gives.
 */
export function formatDate(instant: string | Date, language: string) {
  return formatter(language, {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date(instant))
}

/** When something was recorded, in this device's time zone: "22 October 2026 at 21:20". */
export function formatDateTime(instant: string, language: string) {
  return formatter(language, { dateStyle: 'long', timeStyle: 'short' }).format(
    new Date(instant),
  )
}

/**
 * The day of `instant` in this device's time zone, short, as when an invite link was made:
 * "12 Nov", "12/11". With `weekday`, as when it expires: "Mon, 23 Nov", "seg., 23/11".
 */
export function formatShortDate(
  instant: string,
  language: string,
  { weekday = false } = {},
) {
  return formatter(language, {
    ...(weekday && { weekday: 'short' }),
    day: 'numeric',
    month: 'short',
  }).format(new Date(instant))
}

/** Whether `instant` falls on today's date in this device's time zone. */
export function isToday(instant: string, now = new Date()) {
  return new Date(instant).toDateString() === now.toDateString()
}

/** The same, shorter, for a line of the trail: "22 Oct, 21:20". */
export function formatShortDateTime(instant: string, language: string) {
  return formatter(language, {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(instant))
}

/**
 * A calendar day as the API sends it, `YYYY-MM-DD`: a day, with no time and no time zone. It is
 * read and written as UTC midnight, so no time zone can move it to the day before.
 */
export type Day = string

/** A calendar month, `YYYY-MM`. */
export type Month = string

const DAY = /^(\d{4})-(\d{2})-(\d{2})$/
const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/

function pad(value: number, width = 2) {
  return String(value).padStart(width, '0')
}

/** Today on this device's calendar: the day the person sees where they are. */
export function today(now = new Date()): Day {
  return `${pad(now.getFullYear(), 4)}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

export function isDay(text: string): text is Day {
  const match = DAY.exec(text)
  if (match === null) {
    return false
  }
  const [, year, month, day] = match.map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  return date.getUTCMonth() === month - 1 && date.getUTCDate() === day
}

export function isMonth(text: string): text is Month {
  return MONTH.test(text)
}

export function monthOf(day: Day): Month {
  return day.slice(0, 7)
}

/** `month` moved by `months`: `addMonths('2026-01', -1)` is `2025-12`. */
export function addMonths(month: Month, months: number): Month {
  const [year, number] = month.split('-').map(Number)
  const index = year * 12 + number - 1 + months
  return `${pad(Math.floor(index / 12), 4)}-${pad((index % 12) + 1)}`
}

/** `day` moved by `days`: `addDays('2026-03-01', -1)` is `2026-02-28`. */
export function addDays(day: Day, days: number): Day {
  const date = utcDate(day)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

function utcDate(day: Day) {
  const [year, month, date] = day.split('-').map(Number)
  return new Date(Date.UTC(year, month - 1, date))
}

/** `day` written out with `options` for `language`, without a time zone moving it. */
export function formatDay(
  day: Day,
  language: string,
  options: Omit<Intl.DateTimeFormatOptions, 'timeZone'>,
) {
  return formatter(language, { ...options, timeZone: 'UTC' }).format(
    utcDate(day),
  )
}

/** "October 2026", "outubro de 2026". */
export function formatMonth(month: Month, language: string) {
  return formatDay(`${month}-01`, language, { month: 'long', year: 'numeric' })
}

/** "October", "outubro": the month alone, where its year is shown nearby. */
export function formatMonthName(month: Month, language: string) {
  return formatDay(`${month}-01`, language, { month: 'long' })
}

/** "Friday", "Sexta-feira": as the first word of a heading, so with a capital in any language. */
export function formatWeekday(day: Day, language: string) {
  const weekday = formatDay(day, language, { weekday: 'long' })
  return weekday.charAt(0).toLocaleUpperCase(language) + weekday.slice(1)
}

/**
 * When something happened, for a feed: "Today, 18:40" or "Yesterday, 17:05" (`words`, already
 * translated), the day of the week within the last week ("Saturday, 22:15"), or else the date
 * ("14 Nov, 22:15"). The day is the one on this device's calendar, as for `today`.
 */
export function formatWhen(
  instant: string,
  language: string,
  words: { today: string; yesterday: string },
) {
  const date = new Date(instant)
  const day = today(date)
  const now = today()
  const label =
    day === now
      ? words.today
      : day === addDays(now, -1)
        ? words.yesterday
        : day > addDays(now, -7) && day < now
          ? formatWeekday(day, language)
          : undefined
  if (label === undefined) {
    return formatShortDateTime(instant, language)
  }
  const time = formatter(language, { hour: '2-digit', minute: '2-digit' })
  return `${label}, ${time.format(date)}`
}

/**
 * The heading of a day in a list, newest first: "Today" or "Yesterday" (`words`, already
 * translated) with the long date, or else the day of the week with the date.
 */
export function dayHeading(
  day: Day,
  language: string,
  words: { today: string; yesterday: string },
) {
  const now = today()
  const relative =
    day === now
      ? words.today
      : day === addDays(now, -1)
        ? words.yesterday
        : undefined
  return relative === undefined
    ? {
        label: formatWeekday(day, language),
        date: formatDay(day, language, { day: 'numeric', month: 'long' }),
      }
    : {
        label: relative,
        date: formatDay(day, language, {
          weekday: 'long',
          day: 'numeric',
          month: 'long',
        }),
      }
}

/** The first day of `month`: `2026-10-01`. */
export function firstDayOf(month: Month): Day {
  return `${month}-01`
}

/** The last day of `month`: `2026-02-28`, `2028-02-29`. */
export function lastDayOf(month: Month): Day {
  return addDays(firstDayOf(addMonths(month, 1)), -1)
}

/** "Oct", "out.": the month on a chart's axis, where the year is shown elsewhere. */
export function formatShortMonth(month: Month, language: string) {
  return formatDay(firstDayOf(month), language, { month: 'short' })
}

/**
 * The days from `from` to `to` written out for `language`, with what they share said once:
 * "1–25 October", "1–25 de outubro", "30 September – 25 October".
 */
export function formatDayRange(from: Day, to: Day, language: string) {
  return formatter(language, {
    day: 'numeric',
    month: 'long',
    timeZone: 'UTC',
  }).formatRange(utcDate(from), utcDate(to))
}
