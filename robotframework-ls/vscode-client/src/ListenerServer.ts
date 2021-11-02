import * as http from "http";
import { logRobotFramework } from "./channel";

export class ListenerServer {
    PORT = 5696;
    server: http.Server

    constructor() {
        this.server = http.createServer(this.requestListener)
                          .listen(this.PORT);
    }

    requestListener(request: http.IncomingMessage, response: http.ServerResponse) {
        if (request.method == "POST") {
            let data = [];

            request.on("data", chunk => {
                data.push(chunk);
            });
    
            request.on("end", () => {
                let body = Buffer.concat(data).toString();
                logRobotFramework(body);
                response.end();
            });
        }
    }

    stop() {
        this.server.close();
    }
}
