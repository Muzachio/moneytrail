import { type ComponentProps, useId } from 'react'
import { useTranslation } from 'react-i18next'
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
  InputGroupText,
} from '@/components/ui/input-group'
import { currencyLayout } from '@/lib/money'

type MoneyInputProps = Omit<
  ComponentProps<'input'>,
  'type' | 'inputMode' | 'value' | 'defaultValue' | 'onChange'
> & {
  currency: string
  /** Exactly what the person typed: `12,` stays `12,` while they are still typing. */
  value: string
  onChange: (value: string) => void
}

/**
 * A field for an amount of `currency`, with its symbol where the current language puts it. It
 * holds text; `parseMoneyInput` turns that into minor units, or says why it cannot, when the form
 * is checked.
 */
export function MoneyInput({
  currency,
  value,
  onChange,
  className,
  'aria-describedby': describedBy,
  ...props
}: MoneyInputProps) {
  const { i18n } = useTranslation()
  const { symbol, symbolFirst, zero } = currencyLayout(
    currency,
    i18n.resolvedLanguage ?? 'en',
  )
  const symbolId = useId()

  return (
    <InputGroup className={className}>
      <InputGroupInput
        type="text"
        // The decimal keypad on phones; it shows the comma or the point of the phone's region.
        inputMode="decimal"
        autoComplete="off"
        spellCheck={false}
        placeholder={zero}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        // Screen readers announce the currency after the field's label.
        aria-describedby={describedBy ? `${symbolId} ${describedBy}` : symbolId}
        className="tabular-nums"
        {...props}
      />
      <InputGroupAddon align={symbolFirst ? 'inline-start' : 'inline-end'}>
        <InputGroupText id={symbolId}>{symbol}</InputGroupText>
      </InputGroupAddon>
    </InputGroup>
  )
}
