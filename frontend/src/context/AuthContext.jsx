import { createContext, useContext, useState, useCallback } from 'react';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [token, setTokenState] = useState(() => sessionStorage.getItem('alb_admin_token') || '');

  const setToken = useCallback((newToken) => {
    if (newToken) {
      sessionStorage.setItem('alb_admin_token', newToken);
    } else {
      sessionStorage.removeItem('alb_admin_token');
    }
    setTokenState(newToken || '');
  }, []);

  const logout = useCallback(() => {
    setToken('');
  }, [setToken]);

  const isAuthenticated = token.length > 0;

  return (
    <AuthContext.Provider value={{ token, setToken, logout, isAuthenticated }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
