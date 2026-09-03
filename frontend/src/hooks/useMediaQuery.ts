import { useEffect, useState } from 'react'

/**
 * SSR-safe matchMedia hook. Returns true when the media query matches.
 * Re-evaluates on window resize (media query change events).
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState<boolean>(() =>
    typeof window !== 'undefined' ? window.matchMedia(query).matches : false,
  )

  useEffect(() => {
    const mql = window.matchMedia(query)
    const onChange = () => setMatches(mql.matches)
    setMatches(mql.matches) // 初始同步一次（如 resize 前已加载）
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }, [query])

  return matches
}
