import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import mermaid from 'mermaid'
import { FileText, Files } from 'lucide-react'

interface BlueprintViewerProps {
    content: string
    filesScanned: number
}

// 初始化 Mermaid（全局只需一次）
mermaid.initialize({
    startOnLoad: false,
    theme: 'dark',
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
})

/**
 * 独立的 Mermaid 渲染组件。
 * 关键修复：每个 Mermaid 块作为独立组件，在 useEffect 中直接操作 DOM 渲染 SVG。
 * 避免了原来在父组件 querySelectorAll('.mermaid-pending') 时机不稳定的问题。
 */
let mermaidCounter = 0
function MermaidBlock({ code }: { code: string }) {
    const containerRef = useRef<HTMLDivElement>(null)
    const [error, setError] = useState<string | null>(null)
    const idRef = useRef(`mermaid-${++mermaidCounter}`)

    useEffect(() => {
        const container = containerRef.current
        if (!container) return

        let cancelled = false
        mermaid.render(idRef.current, code)
            .then(({ svg }) => {
                if (!cancelled && container) {
                    container.innerHTML = svg
                    // 让 SVG 自适应宽度
                    const svgEl = container.querySelector('svg')
                    if (svgEl) {
                        svgEl.style.maxWidth = '100%'
                        svgEl.style.height = 'auto'
                    }
                }
            })
            .catch((err) => {
                if (!cancelled) {
                    setError(String(err?.message || err))
                }
            })

        return () => { cancelled = true }
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

export default function BlueprintViewer({ content, filesScanned }: BlueprintViewerProps) {

    return (
        <div className="flex flex-col h-full">
            {/* 面板头 */}
            <div className="flex items-center justify-between px-5 py-3 border-b border-dark-800/80 bg-dark-900/50 flex-shrink-0">
                <div className="flex items-center gap-2">
                    <FileText className="w-4 h-4 text-cyber-500" />
                    <span className="text-sm font-semibold text-gray-200">审计报告</span>
                    <span className="text-[10px] text-dark-400 font-mono ml-1">Project-Blueprint.md</span>
                </div>
                <div className="flex items-center gap-1.5 text-[11px] text-dark-400 font-mono">
                    <Files className="w-3.5 h-3.5" />
                    <span>{filesScanned} 文件</span>
                </div>
            </div>

            {/* Markdown 内容 */}
            <div className="flex-1 overflow-y-auto px-6 py-5">
                <div className="markdown-body">
                    <ReactMarkdown
                        remarkPlugins={[remarkGfm]}
                        components={{
                            code({ className, children, ...props }) {
                                const match = /language-(\w+)/.exec(className || '')
                                const lang = match?.[1]
                                const codeStr = String(children).replace(/\n$/, '')

                                // Mermaid 代码块 → 用独立组件渲染
                                if (lang === 'mermaid') {
                                    return <MermaidBlock code={codeStr} />
                                }

                                // 普通代码块
                                if (lang) {
                                    return (
                                        <div className="relative group">
                                            <div className="absolute top-2 right-2 text-[10px] text-dark-500 font-mono uppercase opacity-60">
                                                {lang}
                                            </div>
                                            <code className={className} {...props}>
                                                {children}
                                            </code>
                                        </div>
                                    )
                                }

                                // 行内代码
                                return (
                                    <code className={className} {...props}>
                                        {children}
                                    </code>
                                )
                            },
                        }}
                    >
                        {content}
                    </ReactMarkdown>
                </div>
            </div>
        </div>
    )
}
