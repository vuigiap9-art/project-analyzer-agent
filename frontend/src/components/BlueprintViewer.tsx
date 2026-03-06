import { useMemo } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { FileText, Files, ListTree } from 'lucide-react'
import MermaidBlock from './MermaidBlock'

interface BlueprintViewerProps {
    content: string
    filesScanned: number
    reasoning?: string
}

export default function BlueprintViewer({ content, filesScanned, reasoning }: BlueprintViewerProps) {
    const headings = useMemo(() => {
        return content
            .split('\n')
            .filter((line) => /^#{1,3}\s+/.test(line))
            .map((line, index) => {
                const level = (line.match(/^#+/)?.[0].length || 1)
                const text = line.replace(/^#{1,3}\s+/, '').trim()
                return {
                    id: `toc-${index}`,
                    level,
                    text,
                }
            })
    }, [content])

    const jumpToHeading = (target: string) => {
        const element = document.getElementById(target)
        if (element) element.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }

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
            <div className="flex-1 min-h-0 flex">
                <aside className="hidden lg:block w-60 border-r border-dark-800/50 px-3 py-4 overflow-y-auto">
                    <div className="flex items-center gap-2 mb-3 text-xs text-dark-300 font-mono">
                        <ListTree className="w-3.5 h-3.5" />
                        文档目录
                    </div>
                    <div className="space-y-1">
                        {headings.length === 0 && <p className="text-[11px] text-dark-500">暂无可导航标题</p>}
                        {headings.map((item) => (
                            <button
                                key={item.id}
                                onClick={() => jumpToHeading(item.id)}
                                className="w-full text-left text-[11px] text-dark-300 hover:text-cyber-400 transition-colors truncate"
                                style={{ paddingLeft: `${(item.level - 1) * 10}px` }}
                            >
                                {item.text}
                            </button>
                        ))}
                    </div>
                </aside>

                <div className="flex-1 overflow-y-auto px-6 py-5">
                    {reasoning && reasoning.trim().length > 0 && (
                        <details className="mb-4 p-3 rounded-lg border border-cyber-700/30 bg-cyber-900/20" open>
                            <summary className="cursor-pointer text-xs font-mono text-cyber-300">Reasoner 思考过程</summary>
                            <div className="markdown-body mt-3">
                                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                                    {reasoning}
                                </ReactMarkdown>
                            </div>
                        </details>
                    )}
                    <div className="markdown-body">
                        <ReactMarkdown
                            remarkPlugins={[remarkGfm]}
                            components={{
                                h1({ children }) {
                                    const text = String(children)
                                    const index = headings.findIndex((item) => item.text === text)
                                    return <h1 id={index >= 0 ? headings[index].id : undefined}>{children}</h1>
                                },
                                h2({ children }) {
                                    const text = String(children)
                                    const index = headings.findIndex((item) => item.text === text)
                                    return <h2 id={index >= 0 ? headings[index].id : undefined}>{children}</h2>
                                },
                                h3({ children }) {
                                    const text = String(children)
                                    const index = headings.findIndex((item) => item.text === text)
                                    return <h3 id={index >= 0 ? headings[index].id : undefined}>{children}</h3>
                                },
                                code({ className, children, ...props }) {
                                    const match = /language-(\w+)/.exec(className || '')
                                    const lang = match?.[1]
                                    const codeStr = String(children).replace(/\n$/, '')

                                    if (lang === 'mermaid') {
                                        return <MermaidBlock code={codeStr} />
                                    }

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
        </div>
    )
}
