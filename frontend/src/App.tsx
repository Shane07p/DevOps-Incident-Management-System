import { BrowserRouter, Link, Navigate, Route, Routes } from 'react-router-dom'
import { canManageServices } from './auth/role'
import ServiceForm from './pages/ServiceForm'
import ServiceList from './pages/ServiceList'

export default function App() {
  const canEdit = canManageServices()
  return (
    <BrowserRouter>
      <header className="border-b px-6 py-3">
        <div className="flex items-center gap-6">
          <h1 className="text-lg font-semibold">Incident Management System</h1>
          <nav>
            <Link to="/services">Services</Link>
          </nav>
        </div>
      </header>
      <main className="px-6 py-4">
        <Routes>
          <Route path="/" element={<Navigate to="/services" replace />} />
          <Route path="/services" element={<ServiceList canEdit={canEdit} />} />
          <Route path="/services/new" element={canEdit ? <ServiceForm /> : <NotAllowed />} />
          <Route path="/services/:id/edit" element={canEdit ? <ServiceForm /> : <NotAllowed />} />
        </Routes>
      </main>
    </BrowserRouter>
  )
}

function NotAllowed() {
  return <p role="alert">Only an Admin can change services.</p>
}
