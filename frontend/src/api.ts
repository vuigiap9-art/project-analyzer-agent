export interface AnalyzeResponse {
    status: string
    filesScanned: number
    auditReport: string
    blueprint: string
    message: string
}

export interface ChatResponse {
    answer: string
    sources: string[]
}

export interface HealthResponse {
    status: string
    service: string
}

const API_BASE = '/api'

export async function analyzeProject(path: string): Promise<AnalyzeResponse> {
    const res = await fetch(`${API_BASE}/analyze?path=${encodeURIComponent(path)}`)
    if (!res.ok) {
        const errorData = await res.json().catch(() => ({}))
        throw new Error(errorData.message || `分析失败 (HTTP ${res.status})`)
    }
    return res.json()
}

export async function chatWithRAG(question: string): Promise<ChatResponse> {
    const res = await fetch(`${API_BASE}/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
    })
    if (!res.ok) {
        const errorData = await res.json().catch(() => ({}))
        throw new Error(errorData.message || `对话失败 (HTTP ${res.status})`)
    }
    return res.json()
}

export async function checkHealth(): Promise<HealthResponse> {
    const res = await fetch(`${API_BASE}/health`)
    if (!res.ok) throw new Error('健康检查失败')
    return res.json()
}
