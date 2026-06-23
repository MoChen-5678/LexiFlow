const https = require('https');

const options = {
    headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36'
    }
};

const url = 'https://raw.githubusercontent.com/Jimshen2022/qwertylearner/master/src/resources/dictionary.ts';
https.get(url, options, (res) => {
    let data = '';
    res.on('data', chunk => data += chunk);
    res.on('end', () => {
        console.log('dictionary.ts:');
        console.log(data.substring(0, 1500));
    });
});
