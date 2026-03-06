import { useMemo, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { AlertTriangle, FileCode2, Loader2, Play, Sparkles } from 'lucide-react'
import { analyzeSnippet } from '../api'

const LANGUAGES = [
    'java',
    'typescript',
    'javascript',
    'python',
    'go',
    'c',
    'cpp',
    'rust',
    'kotlin',
    'other',
]

const DEMO_SNIPPET = `public class PaymentService {
    public boolean pay(User user, int amount) {
        if (user == null) return true;
        if (amount < 0) return true;
        return accountDao.transfer(user.getId(), amount);
    }
}`

export default function SnippetReview() {
    const [path, setPath] = useState('snippet/PaymentService.java')
    const [language, setLanguage] = useState('java')
    const [content, setContent] = useState(DEMO_SNIPPET)
    const [loading, setLoading] = useState(false)
    const [result, setResult] = useState('')
    const [error, setError] = useState('')

    const canSubmit = useMemo(() => {
        return path.trim().length > 0 && content.trim().length > 0 && !loading
    }, [path, content, loading])

    const submit = async (event: React.FormEvent) => {
        event.preventDefault()
        if (!canSubmit) return
        setLoading(true)
        setError('')
        setResult('')
        try {
            const data = await analyzeSnippet({
                path: path.trim(),
                language,
                content,
            })
            setResult(data.analysis)
        } catch (err: any) {
            setError(err?.message || '请求失败')
        } finally {
            setLoading(false)
        }
    }

    return (
        <section className="h-full flex flex-col lg:flex-row gap-3 p-3">
            <form onSubmit={submit} className="lg:w-1/2 glass-panel p-4 flex flex-col gap-3">
                <div className="flex items-center gap-2">
                    <FileCode2 className="w-4 h-4 text-cyber-500" />
                    <h3 className="text-sm font-semibold text-gray-100">Snippet Code Review</h3>
                </div>

                <label className="text-xs text-dark-300 font-mono">文件路径</label>
                <input
                    value={path}
                    onChange={(event) => setPath(event.target.value)}
                    className="cyber-input"
                    placeholder="src/main/java/com/example/Foo.java"
                />

                <div className="grid grid-cols-2 gap-3">
                    <div className="col-span-2 sm:col-span-1">
                        <label className="text-xs text-dark-300 font-mono">语言</label>
                        <select
                            className="cyber-input w-full mt-1"
                            value={language}
                            onChange={(event) => setLanguage(event.target.value)}
                        >
                            {LANGUAGES.map((item) => (
                                <option key={item} value={item}>
                                    {item}
                                </option>
                            ))}
                        </select>
                    </div>
                    <div className="col-span-2 sm:col-span-1 flex items-end">
                        <button className="cyber-btn w-full flex items-center justify-center gap-2" disabled={!canSubmit}>
                            {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
                            开始审查
                        </button>
                    </div>
                </div>

                <label className="text-xs text-dark-300 font-mono">代码内容</label>
                <textarea
                    value={content}
                    onChange={(event) => setContent(event.target.value)}
                    className="cyber-input min-h-[280px] resize-y"
                    spellCheck={false}
                />
            </form>

            <div className="lg:w-1/2 glass-panel p-4 overflow-y-auto">
                <div className="flex items-center gap-2 mb-3">
                    <Sparkles className="w-4 h-4 text-cyber-400" />
                    <h3 className="text-sm font-semibold text-gray-100">审查结果</h3>
                </div>

                {error && (
                    <div className="flex items-start gap-2 p-3 rounded-lg border border-red-500/30 bg-red-950/20 text-xs text-red-300">
                        <AlertTriangle className="w-4 h-4 mt-0.5" />
                        <span>{error}</span>
                    </div>
                )}

                {!error && !result && (
                    <p className="text-xs text-dark-400 font-mono">提交代码片段后，将在此显示风险、建议和改进思路。</p>
                )}

                {result && (
                    <div className="markdown-body">
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                            {result}
                        </ReactMarkdown>
                    </div>
                )}
            </div>
        </section>
    )
}
