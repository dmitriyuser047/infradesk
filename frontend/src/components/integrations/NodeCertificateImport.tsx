import { useEffect, useRef, useState } from 'react'
import { importNodeCertificate } from '../../api/nodeOnboarding'
import { ApiError } from '../../api/httpClient'
import { useI18n } from '../../i18n'
import { InlineAlert } from '../layout/WorkspacePrimitives'

export function NodeCertificateImport({ organizationId, integrationId, resourceId, domain, onImported }: {
  organizationId: string; integrationId: string; resourceId: string; domain: string; onImported: (id: string) => void
}) {
  const { locale, format } = useI18n()
  const ru = locale === 'ru'
  const certificate = useRef<HTMLInputElement>(null); const key = useRef<HTMLInputElement>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [expires, setExpires] = useState<string | null>(null)
  const generation = useRef(0)
  useEffect(() => {
    generation.current += 1
    setPending(false); setError(null); setExpires(null)
    if (certificate.current) certificate.current.value = ''
    if (key.current) key.current.value = ''
    return () => { generation.current += 1 }
  }, [organizationId, integrationId, resourceId, domain])
  const submit = async () => {
    if (pending) return
    const certFile = certificate.current?.files?.[0]; const keyFile = key.current?.files?.[0]
    if (!certFile || !keyFile || certFile.size > 32768 || keyFile.size > 16384) {
      setError(ru ? 'Выберите цепочку PEM (до 32 КБ) и закрытый ключ PKCS#8 (до 16 КБ).' :
        'Select a PEM chain (up to 32 KB) and a PKCS#8 private key (up to 16 KB).'); return
    }
    setPending(true); setError(null)
    const submittedGeneration = generation.current
    try {
      const result = await importNodeCertificate(organizationId, integrationId, {
        resourceId, domain, certificatePem: await certFile.text(), privateKeyPem: await keyFile.text(),
      })
      if (generation.current !== submittedGeneration) return
      setExpires(result.expiresAt); onImported(result.id)
      if (certificate.current) certificate.current.value = ''
      if (key.current) key.current.value = ''
    } catch (failure) {
      if (generation.current !== submittedGeneration) return
      const code = failure instanceof ApiError ? failure.code : ''
      const errors: Record<string, [string, string]> = {
        REMNAWAVE_TLS_SAN_MISMATCH: ['Сертификат не покрывает указанный домен.', 'The certificate does not cover this domain.'],
        REMNAWAVE_TLS_KEY_MISMATCH: ['Ключ не соответствует сертификату. Нужен незашифрованный ключ PKCS#8.', 'The key does not match. Use an unencrypted PKCS#8 private key.'],
        REMNAWAVE_TLS_EXPIRY_TOO_CLOSE: ['Сертификат ещё не действует или истекает менее чем через 7 дней.', 'The certificate is not yet valid or expires within 7 days.'],
        REMNAWAVE_TLS_CHAIN_UNTRUSTED: ['Цепочка сертификата не доверена. Добавьте промежуточные сертификаты CA.', 'The chain is not trusted. Include the intermediate CA certificates.'],
      }
      setError(errors[code]?.[ru ? 0 : 1] ?? (ru ? 'Не удалось импортировать сертификат. Проверьте PEM-файлы и повторите.' : 'Could not import the certificate. Check the PEM files and retry.'))
    } finally { if (generation.current === submittedGeneration) setPending(false) }
  }
  return <fieldset disabled={pending}>
    <legend>{ru ? 'TLS-сертификат для Hysteria2' : 'TLS certificate for Hysteria2'}</legend>
    <p>{ru ? `Сертификат должен покрывать ${domain}. Ключ хранится зашифрованным и подключается к контейнеру только для чтения.` :
      `The certificate must cover ${domain}. The key is encrypted at rest and mounted read-only in the container.`}</p>
    <label className="field">{ru ? 'Цепочка сертификата (.pem)' : 'Certificate chain (.pem)'}
      <input type="file" accept=".pem,.crt" ref={certificate} /></label>
    <label className="field">{ru ? 'Закрытый ключ PKCS#8 (.key)' : 'PKCS#8 private key (.key)'}
      <input type="file" accept=".key,.pem" ref={key} /></label>
    <button type="button" className="secondary-button" onClick={() => void submit()} disabled={pending}>
      {pending ? ru ? 'Проверка…' : 'Checking…' : ru ? 'Проверить и импортировать' : 'Validate and import'}</button>
    {error ? <InlineAlert tone="danger" title={error} /> : null}
    {expires ? <p role="status">{ru ? 'Сертификат проверен. Действует до' : 'Certificate validated. Expires'} {format.dateTime(expires)}</p> : null}
  </fieldset>
}
