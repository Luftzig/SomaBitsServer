config.devServer = config.devServer || {}
config.devServer.port = 8080
config.devServer.proxy = {
    '/': {
        target: 'http://localhost:8081',
        bypass: function (req, res, proxyOptions) {
            if (req.headers.accept.indexOf('html') !== -1) {
                console.log('Skipping proxy for browser request.');
                return '/index.html';
            }
        }
    },
    '/ws': {
        target: 'ws://localhost:8081',
        ws: true
    }
}
