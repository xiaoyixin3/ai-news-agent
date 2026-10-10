package cn.zaozhi.controller;

import cn.zaozhi.Json;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public final class RequestBoundaryFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        if(request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control","no-store");
        if(!Set.of("127.0.0.1","localhost","[::1]","::1").contains(request.getServerName())) { reject(response,403,"仅接受本机访问"); return; }
        String origin=request.getHeader("Origin");
        if(origin!=null) {
            try {
                URI source=URI.create(origin);
                int port=source.getPort()==-1?("https".equals(source.getScheme())?443:80):source.getPort();
                if(!source.getScheme().equals(request.getScheme()) || !source.getHost().equalsIgnoreCase(request.getServerName()) || port!=request.getServerPort()) { reject(response,403,"不接受跨站请求"); return; }
            } catch(RuntimeException e) { reject(response,403,"来源地址无效"); return; }
        }
        if(request.getRequestURI().startsWith("/api/") && Set.of("POST","PUT").contains(request.getMethod())) {
            byte[] bytes=request.getInputStream().readNBytes(65537);
            if(bytes.length>65536) { reject(response,413,"请求内容超过 64KB"); return; }
            HttpServletRequestWrapper wrapped=new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    ByteArrayInputStream stream=new ByteArrayInputStream(bytes);
                    return new ServletInputStream() {
                        public int read() { return stream.read(); }
                        public boolean isFinished() { return stream.available()==0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
            };
            chain.doFilter(wrapped,response); return;
        }
        chain.doFilter(request,response);
    }
    private static void reject(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8"); response.getWriter().write(Json.stringify(Json.object("error",message)));
    }
}
