package dev.veil.scanner;

interface IScannerService {
    String scan(String root) = 1;
    void destroy() = 16777114;
}
