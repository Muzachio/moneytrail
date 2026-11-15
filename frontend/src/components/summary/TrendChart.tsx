import {
  Bar,
  BarChart,
  type BarShapeProps,
  CartesianGrid,
  Rectangle,
  XAxis,
  YAxis,
} from 'recharts'
import type { MonthTotals } from '@/api/reports'
import { CURRENCY } from '@/lib/accounts'
import { formatShortMonth } from '@/lib/dates'
import { currencyDigits, formatCompactMoney } from '@/lib/money'
import { axisTicks } from '@/lib/reports'

/** Recharts' active index: a number for a bar chart, but typed for every kind of chart. */
function indexOf(active: unknown): number | undefined {
  const index = Number(active)
  return active === undefined || active === null || Number.isNaN(index)
    ? undefined
    : index
}

const axisTick = { fill: 'var(--muted-foreground)', fontSize: 12 }

/**
 * The bars of the six-month trend, drawn with Recharts: income on the left in teal, spending on
 * the right in blue, as in the design canvas. In a module of its own, which the page loads only
 * on a wide screen: the phone's summary has no trend, and does not download Recharts.
 */
export default function TrendChart({
  months,
  hovered,
  onHover,
  language,
}: {
  months: MonthTotals[]
  hovered: number | undefined
  onHover: (index: number | undefined) => void
  language: string
}) {
  // Heights in minor units, whole numbers as the API sends them; only the axis' labels are text.
  const data = months.map((month) => ({
    label: formatShortMonth(month.month, language),
    income: month.income.amountMinor,
    spending: month.spending.amountMinor,
  }))
  const ticks = axisTicks(
    Math.max(...data.flatMap(({ income, spending }) => [income, spending])),
    10 ** currencyDigits(CURRENCY),
  )
  // The month under the pointer stays bright and the others fade, as in the design canvas.
  const shape = (props: BarShapeProps) => (
    <Rectangle
      {...props}
      fillOpacity={hovered === undefined || hovered === props.index ? 1 : 0.35}
    />
  )

  return (
    <BarChart
      responsive
      data={data}
      style={{ width: '100%', height: '100%' }}
      margin={{ top: 8, right: 0, bottom: 0, left: 0 }}
      barGap={2}
      barCategoryGap="30%"
      // Keyboard and screen reader users have the table, which reads better than the chart.
      accessibilityLayer={false}
      onMouseMove={(state) => onHover(indexOf(state.activeTooltipIndex))}
      onMouseLeave={() => onHover(undefined)}
    >
      <CartesianGrid vertical={false} stroke="var(--border)" />
      <XAxis
        dataKey="label"
        axisLine={false}
        tickLine={false}
        tick={axisTick}
      />
      <YAxis
        // Room for the longest label, "3 mil €" in Portuguese.
        width={56}
        axisLine={false}
        tickLine={false}
        tick={axisTick}
        domain={[0, ticks[ticks.length - 1]]}
        ticks={ticks}
        tickFormatter={(amountMinor: number) =>
          formatCompactMoney({ amountMinor, currency: CURRENCY }, language)
        }
      />
      <Bar
        dataKey="income"
        fill="var(--color-income)"
        maxBarSize={12}
        radius={[4, 4, 0, 0]}
        isAnimationActive={false}
        shape={shape}
      />
      <Bar
        dataKey="spending"
        fill="var(--color-spending)"
        maxBarSize={12}
        radius={[4, 4, 0, 0]}
        isAnimationActive={false}
        shape={shape}
      />
    </BarChart>
  )
}
