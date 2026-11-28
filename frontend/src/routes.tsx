import type { RouteObject } from 'react-router'
import { AppLayout } from '@/components/AppLayout'
import { AccountsPage } from '@/pages/AccountsPage'
import { GroupExpenseSheet } from '@/pages/GroupExpenseSheet'
import { GroupPage } from '@/pages/GroupPage'
import { GroupPaymentSheet } from '@/pages/GroupPaymentSheet'
import { GroupsPage } from '@/pages/GroupsPage'
import { JoinPage } from '@/pages/JoinPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { PrivacyPage } from '@/pages/PrivacyPage'
import { SummaryPage } from '@/pages/SummaryPage'
import { TransactionDetailSheet } from '@/pages/TransactionDetailSheet'
import { TransactionsPage } from '@/pages/TransactionsPage'

export const routes: RouteObject[] = [
  // Outside AppLayout, so it opens without a session: the sign-in page links to it.
  { path: '/privacy', element: <PrivacyPage /> },
  // Outside it too: an invite link opens it before the person has signed in (ADR-0013).
  { path: '/join', element: <JoinPage /> },
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <SummaryPage /> },
      { path: 'accounts', element: <AccountsPage /> },
      {
        path: 'transactions',
        element: <TransactionsPage />,
        children: [{ path: ':id', element: <TransactionDetailSheet /> }],
      },
      { path: 'groups', element: <GroupsPage /> },
      {
        path: 'groups/:id',
        element: <GroupPage />,
        children: [
          { path: 'expenses/:expenseId', element: <GroupExpenseSheet /> },
          { path: 'payments/:settlementId', element: <GroupPaymentSheet /> },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
