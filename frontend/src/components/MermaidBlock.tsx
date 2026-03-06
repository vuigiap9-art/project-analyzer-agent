import { useEffect, useRef, useState } from 'react'
import mermaid from 'mermaid'

mermaid.initialize({
    startOnLoad: false,
    theme: 'dark',
    suppressErrorRendering: true,
    themeVariables: {
        primaryColor: '#008075',
        primaryTextColor: '#e0fffe',
        primaryBorderColor: '#00b3a0',
        lineColor: '#00b3a0',
        secondaryColor: '#1e1f23',
        tertiaryColor: '#121316',
        fontFamily: 'JetBrains Mono, monospace',
        fontSize: '12px',
    },
} as any)

let mermaidCounter = 0

export default function MermaidBlock({ code }: { code: string }) {
    const containerRef = useRef<HTMLDivElement>(null)
    const [error, setError] = useState<string | null>(null)
    const idRef = useRef(`mermaid-${++mermaidCounter}`)

    useEffect(() => {
        const container = containerRef.current
        if (!container) return

        let cancelled = false
        setError(null)
        container.innerHTML = ''

        const normalizedCode = normalizeMermaidCode(code)
        const candidates = buildCandidates(normalizedCode)

        ;(async () => {
            try {
                let lastErr: any = null
                for (let i = 0; i < candidates.length; i++) {
                    const candidate = candidates[i]
                    try {
                        await mermaid.parse(candidate, { suppressErrors: true } as any)
                        if (cancelled || !container) return
                        const { svg } = await mermaid.render(`${idRef.current}-${i}`, candidate)
                        if (!cancelled && container) {
                            container.innerHTML = svg
                            const svgEl = container.querySelector('svg')
                            if (svgEl) {
                                svgEl.style.maxWidth = '100%'
                                svgEl.style.height = 'auto'
                            }
                        }
                        return
                    } catch (err: any) {
                        lastErr = err
                    }
                }
                throw lastErr || new Error('Mermaid parse failed')
            } catch (err: any) {
                if (!cancelled) {
                    setError(String(err?.message || err || '未知错误'))
                }
            }
        })()

        return () => {
            cancelled = true
            if (container) {
                container.innerHTML = ''
            }
        }
    }, [code])

    if (error) {
        return (
            <div className="my-4 p-3 bg-red-950/30 border border-red-500/30 rounded-lg">
                <p className="text-red-400 text-xs font-mono">Mermaid 渲染失败</p>
                <pre className="text-dark-400 text-xs mt-1 overflow-x-auto">{code}</pre>
            </div>
        )
    }

    return (
        <div
            ref={containerRef}
            className="my-4 p-4 bg-dark-900/60 border border-dark-700/50 rounded-lg overflow-x-auto flex justify-center"
        />
    )
}

function normalizeMermaidCode(raw: string): string {
    return (raw || '')
        .trim()
        .replace(/\u00A0/g, ' ')
        .replace(/[\u201C\u201D]/g, '"')
        .replace(/[\u2018\u2019]/g, "'")
        .replace(/[（]/g, '(')
        .replace(/[）]/g, ')')
        .replace(/[【]/g, '[')
        .replace(/[】]/g, ']')
        .replace(/[，]/g, ',')
        .replace(/[：]/g, ':')
        .replace(/[；]/g, ';')
}

function buildCandidates(base: string): string[] {
    const candidates = [base]
    candidates.push(
        base
            .replace(/<br\s*\/>/gi, '<br>')
            .replace(/^\s*subgraph\s+"([^"]+)"\s*$/gim, 'subgraph $1')
            .replace(/^\s*subgraph\s+'([^']+)'\s*$/gim, 'subgraph $1'),
    )
    candidates.push(
        base
            .replace(/^\s*subgraph\s+"([^"]+)"\s*$/gim, 'subgraph $1')
            .replace(/^\s*subgraph\s+'([^']+)'\s*$/gim, 'subgraph $1')
            .replace(/\[([^\]]+)<br\s*\/?>/gi, '[$1\\n')
            .replace(/<br\s*\/?>/gi, '\\n'),
    )

    const dedup = Array.from(new Set(candidates.map((item) => item.trim()).filter(Boolean)))
    return dedup.length ? dedup : [base]
}
