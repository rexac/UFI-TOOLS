const fs = require('fs');
const path = require('path');
const JavaScriptObfuscator = require('javascript-obfuscator');

const isDebug = process.argv.includes('--debug');
const inputDir = path.resolve(__dirname, 'script_orignal');
const outputDir = path.resolve(__dirname, 'script');

const firstChars = ['o', 'O'];      // 首字符合法：不能为数字
const otherChars = ['0', 'o', 'O','p','P','m','n']; // 其余字符可自由组合
const maxLength = 6;                // 控制生成最大长度
const result = [];

function generate(current, isFirst) {
    if (current.length > 0) {
        result.push(current);
    }
    if (current.length === maxLength) return;

    const chars = isFirst ? firstChars : otherChars;
    for (const c of chars) {
        generate(current + c, false);
    }
}

for (const c of firstChars) {
    generate(c, false);
}

fs.writeFileSync('dictionary.json', JSON.stringify(result, null, 2));
console.log(`✅ 生成 ${result.length} 个合法变量名，已写入 dictionary.json`);

const obfuscateOptions = {
    compact: true,
    controlFlowFlattening: !isDebug,
    controlFlowFlatteningThreshold: 1.0,
    deadCodeInjection: !isDebug,
    deadCodeInjectionThreshold: 1.0,
    disableConsoleOutput: !isDebug,
    identifierNamesGenerator: 'hexadecimal',
    stringArray: true,
    renameGlobals: false,
    stringArrayThreshold: 1.0,
    transformObjectKeys: true,
    unicodeEscapeSequence: true,
    identifierNamesGenerator: 'dictionary',
    identifiersDictionary: require('./dictionary.json')
};

if (fs.existsSync(outputDir)) {
    fs.rmSync(outputDir, { recursive: true, force: true });
    console.log(`🧹 已删除旧的输出目录: ${outputDir}`);
}

fs.mkdirSync(outputDir, { recursive: true });

function copyOrObfuscateFile(entryPath, outPath) {
    const sourceCode = fs.readFileSync(entryPath, 'utf8');
    if (isDebug) {
        fs.writeFileSync(outPath, sourceCode, 'utf8');
        console.log(`🔄 Copied (debug): ${entryPath} -> ${outPath}`);
    } else {
        const obfuscatedCode = JavaScriptObfuscator.obfuscate(sourceCode, obfuscateOptions).getObfuscatedCode();
        fs.writeFileSync(outPath, obfuscatedCode, 'utf8');
        console.log(`✔️ Obfuscated: ${entryPath} -> ${outPath}`);
    }
}

// 递归处理目录
function processDirectory(dir, outDir) {
    const entries = fs.readdirSync(dir);

    entries.forEach((entry) => {
        const entryPath = path.join(dir, entry);
        const outPath = path.join(outDir, entry);
        const stat = fs.statSync(entryPath);

        if (stat.isDirectory()) {
            fs.mkdirSync(outPath, { recursive: true });
            processDirectory(entryPath, outPath);
        } else if (stat.isFile()) {
            if (entry.endsWith('.js')) {
                copyOrObfuscateFile(entryPath, outPath);
            } else {
                // 非 JS 文件直接复制
                fs.copyFileSync(entryPath, outPath);
                console.log(`📄 Copied (non-JS): ${entryPath} -> ${outPath}`);
            }
        }
    });
}

if (isDebug) {
    console.log('[DEBUG] Debug 模式已启用，文件将原样复制，无混淆。');
}

processDirectory(inputDir, outputDir);
console.log('\n✅ 所有文件处理完毕！');