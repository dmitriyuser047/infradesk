export interface AdministrationUser {
  id: string; email: string; displayName: string; isActive: boolean; isAdministrator: boolean; updatedAt: string
}
export interface AdministrationOrganization { id: string; code: string; name: string }
export interface AdministrationMember {
  user: AdministrationUser; organizationId: string; organizationName: string; role: string; isActive: boolean; updatedAt: string
}
export interface AdministrationPage<T> { items: T[]; nextCursor: string | null }
export interface CreateAdministrationUserRequest {
  requestId: string; email: string; displayName: string; password: string; organizationId: string | null; role: string; isAdministrator: boolean
}
export interface ChangeMembershipRequest { role: string; isActive: boolean; expectedUpdatedAt: string | null }
