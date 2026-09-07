import { request } from '@/api/client'
import { getRuntimeConfig, joinApiUrl } from '@/config/runtime'

export type AesMode = 'CBC' | 'ECB' | 'OFB' | 'CFB' | 'CTS' | 'CTR' | 'GCM'
export type AesPadding = 'PKCS7' | 'NONE' | 'ZEROS' | 'ANSIX923' | 'ISO10126'

export interface AesRequest {
  compatibilityProfile: 'TOOLHELPER_V3'
  mode: AesMode
  padding: AesPadding
  keySizeBits: 128 | 192 | 256
  charset: 'UTF-8' | 'UTF-16LE' | 'UTF-32LE' | 'ASCII' | 'GBK' | 'ISO-8859-1'
  keyFormat: 'TEXT' | 'HEX' | 'BASE64'
  ivFormat: 'TEXT' | 'HEX' | 'BASE64'
  inputFormat: 'TEXT' | 'HEX' | 'BASE64'
  outputFormat: 'TEXT' | 'HEX' | 'BASE64'
  key: string
  iv: string
  input: string
}

export interface AesResult {
  output: string
  mode: AesMode
  padding: AesPadding
  outputFormat: string
}

const javaBase = () => getRuntimeConfig().javaApiBase

export function encryptAes(payload: AesRequest): Promise<AesResult> {
  return request<AesResult>(joinApiUrl(javaBase(), '/api/java/crypto/aes/encrypt'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}

export function decryptAes(payload: AesRequest): Promise<AesResult> {
  return request<AesResult>(joinApiUrl(javaBase(), '/api/java/crypto/aes/decrypt'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
}
