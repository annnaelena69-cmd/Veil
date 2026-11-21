package dev.veil.scanner;

interface IScannerService {
    String scan(String root);
    void destroy() = 16777114;
}
