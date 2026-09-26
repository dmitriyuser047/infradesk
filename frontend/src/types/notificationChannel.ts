export type NotificationChannelType = 'WEBHOOK' | 'TELEGRAM' | 'EMAIL'
export type NotificationEventType = 'INCIDENT_OPENED' | 'INCIDENT_RESOLVED'
export type IncidentReason = 'THRESHOLD' | 'NO_DATA'
export type EmailSecurity = 'NONE' | 'STARTTLS' | 'TLS'

export interface NotificationChannelResponse {
  id: string
  name: string
  type: NotificationChannelType
  enabled: boolean
  events: NotificationEventType[]
  reasons: IncidentReason[]
  config: WebhookConfig | TelegramConfig | EmailConfig
  createdAt: string
  updatedAt: string
}
export interface WebhookConfig { credentialConfigured: boolean }
export interface TelegramConfig { credentialConfigured: boolean; chatId: string }
export interface EmailConfig {
  credentialConfigured: boolean; smtpHost: string; smtpPort: number; security: EmailSecurity
  username: string; fromAddress: string; recipients: string[]
}
export type SaveNotificationChannelRequest = {
  name: string; type: NotificationChannelType; events: NotificationEventType[]; reasons: IncidentReason[]
  enabled?: boolean; webhook?: { url?: string }; telegram?: { chatId: string; botToken?: string }
  email?: { smtpHost: string; smtpPort: number; security: EmailSecurity; username: string
    password?: string; fromAddress: string; recipients: string[] }
}
export interface TestNotificationResponse { status: 'SENT' | 'RETRYABLE_FAILURE' | 'PERMANENT_FAILURE'; code?: string | null }
