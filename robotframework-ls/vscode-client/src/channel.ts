import { window } from "vscode";

export const OUTPUT_CHANNEL_NAME = "Robot Framework";
export const OUTPUT_CHANNEL = window.createOutputChannel(OUTPUT_CHANNEL_NAME);
export const RF_LOG_CHANNEL = window.createOutputChannel("Robot Framework Log");

type LogEntry = {
    logLevel: string
    testName: string
    keyword: string
    message: string
}

export function clearRobotFrameworkLog() {
    RF_LOG_CHANNEL.clear();
}

export async function logRobotFramework(logEntry: string) {
    const { logLevel, testName, keyword, message } = JSON.parse(logEntry) as LogEntry
    let logMessage = `Test: ${testName}\nKeyword: ${keyword}\n${logLevel} - ${message}`
    RF_LOG_CHANNEL.appendLine(logMessage);
}

export function logError(msg: string, err: Error) {
    OUTPUT_CHANNEL.appendLine(msg);
    let indent = "    ";
    if (err.message) {
        OUTPUT_CHANNEL.appendLine(indent + err.message);
    }
    if (err.stack) {
        let stack: string = "" + err.stack;
        OUTPUT_CHANNEL.appendLine(stack.replace(/^/gm, indent));
    }
}
