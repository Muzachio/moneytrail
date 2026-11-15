import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import './i18n/i18n'
import './index.css'
import { listenForInstallPrompt } from './pwa/install'
import { UpdatePrompt } from './pwa/UpdatePrompt'
import { routes } from './routes'

listenForInstallPrompt()

const queryClient = new QueryClient()
const router = createBrowserRouter(routes)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <UpdatePrompt />
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
