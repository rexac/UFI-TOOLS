// 图表更新器
const chartUpdater = (prop, value) => {
    switch (prop) {
        case 'cpu_usage':
            updateCpuChart && updateCpuChart(value);
            break
        case 'realtime_rx_thrpt':
            updateNetworkChart && updateNetworkChart(value)
            break
        case 'cpu_temp':
            updateTempChart && updateTempChart(value)
            break
        case 'mem_usage':
            updateMemChart && updateMemChart(value)
            break
        case 'cpuUsageInfo':
            updateCpuCoreChart && updateCpuCoreChart(value)
            break
        case 'cpuFreqInfo':
            if (value) {
                const cpuFreqList = document.querySelector('#cpuFreqList')
                let html = ''
                for (let i = 0; value[`cpu${i}`] != undefined && value[`cpu${i}`] != null; i++) {
                    let cur = String(value[`cpu${i}`].cur)
                    let cur_origin = String(value[`cpu${i}`].cur)
                    let max = String(value[`cpu${i}`].max)
                    if (cur.length == 1) cur = `&nbsp;&nbsp;&nbsp;${cur}`
                    else if (cur.length == 2) cur = `&nbsp;&nbsp;${cur}`
                    else if (cur.length == 3) cur = `&nbsp;${cur}`
                    html += `${kano_parseSignalBar(cur_origin, 0, max, max * 0.9, max * 0.9, {
                        g: '#ffa5008f',
                        o: '#ffa5008f',
                        r: '#40A7EC'
                    })}`

                }
                cpuFreqList.innerHTML = html
            }
            break
        case 'memInfo':
            if (value) {
                const memInfo = document.querySelector('#memInfo')
                memInfo.innerHTML = `<div>内存(全部)：${formatBytes(value['mem_total_kb'] * 1024)}</div>
                <div>内存(可用)：${formatBytes(value['mem_available_kb'] * 1024)}</div>
                <div>内存(已用)：${formatBytes(value['mem_used_kb'] * 1024)}(${Math.round(value['mem_usage_percent'])}%)</div>
                <div>全部SWAP：${formatBytes(value['swap_total_kb'] * 1024)}</div>
                <div>已用SWAP：${formatBytes(value['swap_used_kb'] * 1024)}(${Math.round(value['swap_usage_percent'])}%)</div>
                <div>可用SWAP：${formatBytes(value['swap_free_kb'] * 1024)}</div>`
            }
            break
    }
}

const MAX_length = 20
const ANI_DURATION = 300

//cpu占用
const updateCpuChart = (() => {
    const canvas = document.getElementById('kanoCpuChart');
    const ctx = canvas.getContext('2d');
    const labels = Array(MAX_length).fill('0')
    const data = Array(MAX_length).fill(0)

    Chart.register(centerTextPlugin);
    const chart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels,
            datasets: [{
                data,
                tension: 0.5,
                pointRadius: 0,
                fill: true,
                backgroundColor: '#40A7EC',
                borderColor: '#40A7EC',
                borderRadius: 3,
                borderSkipped: false,
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: {
                duration: ANI_DURATION,    // 0.5秒动画
                easing: 'easeOutQuad'  // 自然缓动效果
            },
            plugins: {
                legend: { display: false },
            },
            scales: {
                x: {
                    grid: { display: false },
                    ticks: { display: false },
                    border: { display: false }
                },
                y: {
                    grid: { display: false },
                    ticks: { display: false },
                    border: { display: false },
                    max: 100,
                    min: 0
                }
            }
        }
    });

    return (value) => {
        if (value != undefined || value != null) {
            chart.options.plugins.centerText.text = [
                { text: `CPU: ${Math.floor(value)} % ` }
            ]
            labels.length > MAX_length && labels.shift()
            data.length > MAX_length && data.shift()

            labels.push(Number(labels[labels.length - 1]) + 1)
            data.push(Number(value))


            chart.update()
        }
    }
})()

//cpu核心占用
const updateCpuCoreChart = (() => {
    const canvas = document.getElementById('kanoCpuCoreChart');
    const ctx = canvas.getContext('2d');
    const labels = ['核心1', '核心2', '核心3', '核心4', '核心5', '核心6', '核心7', '核心8']
    const data = Array(8).fill(0)

    Chart.register(centerTextPlugin);
    const chart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels,
            datasets: [{
                data,
                tension: 0.5,
                pointRadius: 0,
                fill: true,
                backgroundColor: '#40A7EC',
                borderColor: '#40A7EC',
                borderRadius: 3,
                borderSkipped: false,
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: {
                duration: ANI_DURATION,    // 0.5秒动画
                easing: 'easeOutQuad'  // 自然缓动效果
            },
            plugins: {
                legend: { display: false },
            },
            scales: {
                x: {
                    grid: { display: false },
                    ticks: { display: false },
                    border: { display: false }
                },
                y: {
                    grid: { display: false },
                    ticks: { display: false },
                    border: { display: false },
                    max: 100,
                    min: 0
                }
            }
        }
    });

    return (value) => {
        if (value != undefined || value != null) {
            for (let index = 0; index < 8; index++) {
                if (value[`cpu${index}`] != undefined) {
                    data[index] = Math.round(value[`cpu${index}`])
                }
            }
            chart.update()
        }
    }
})()

//内存占用
const updateMemChart = (() => {
    const canvas = document.getElementById('kanoMemChart');
    const ctx = canvas.getContext('2d');
    const labels = Array(MAX_length).fill('0')
    const data = Array(MAX_length).fill(0)

    Chart.register(centerTextPlugin);
    const chart = new Chart(ctx, {
        type: 'line',
        data: {
            labels,
            datasets: [{
                data,
                borderColor: '#40A7EC',
                tension: 0.5,
                pointRadius: 0,
                fill: false,
                borderWidth: 2,
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: {
                duration: ANI_DURATION,    // 0.5秒动画
                easing: 'easeOutQuad'  // 自然缓动效果
            },
            plugins: {
                legend: { display: false },
            },
            scales: {
                x: {
                    grid: { display: false },
                    ticks: { display: false },
                    border: { display: false }
                },
                y: {
                    grid: { display: false },
                    ticks: { display: false },
                    border: { display: false },
                    max: 100,
                    min: 0
                }
            }
        }
    });

    return (value) => {
        if (value != undefined || value != null) {
            chart.options.plugins.centerText.text = [
                { text: `内存: ${Math.floor(value)} % ` }
            ]

            let newLabels = [...labels]
            let newData = [...data]

            newLabels.push(Number(newLabels[newLabels.length - 1]) + 1)
            newData.push(Number(value))

            newLabels.length > MAX_length && newLabels.shift()
            newData.length > MAX_length && newData.shift()

            labels.forEach((_, index) => {
                labels[index] = newLabels[index]
            })
            data.forEach((_, index) => {
                data[index] = newData[index]
            })

            chart.update()
        }
    }
})()

// CPU温度图表
const updateTempChart = (() => {
    const canvas = document.getElementById('kanoTempChart');
    const ctx = canvas.getContext('2d');
    const labels = Array(MAX_length).fill('0')
    const data = Array(MAX_length).fill(0)

    Chart.register(centerTextPlugin);
    const chart = new Chart(ctx, {
        type: 'line',
        data: {
            labels,
            datasets: [{
                data,
                borderColor: '#40A7EC',
                tension: 0.5,
                pointRadius: 0,
                borderWidth: 2,
                fill: false
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: {
                duration: ANI_DURATION,
                easing: 'easeOutQuad',
            },
            plugins: {
                legend: { display: false },
            },
            scales: {
                x: {
                    grid: { display: false },
                    ticks: { display: false },
                    border: { display: false }
                },
                y: {
                    grid: { display: false },
                    ticks: { display: false },
                    border: { display: false },
                    max: 110,
                    min: 0
                }
            }
        }
    });

    return (value) => {
        if (value != undefined || value != null) {
            chart.options.plugins.centerText.text = [
                { text: `温度: ${String(Number(value / 1000).toFixed(2))}℃` }
            ]
            let newLabels = [...labels]
            let newData = [...data]

            newLabels.push(Number(newLabels[newLabels.length - 1]) + 1)
            newData.push(Number(value / 1000).toFixed(2))

            newLabels.length > MAX_length && newLabels.shift()
            newData.length > MAX_length && newData.shift()

            labels.forEach((_, index) => {
                labels[index] = newLabels[index]
            })
            data.forEach((_, index) => {
                data[index] = newData[index]
            })

            chart.update()
        }
    }
})()

//网速图表
const updateNetworkChart = (() => {
    const canvas = document.getElementById('kanoNetChart');
    const ctx = canvas.getContext('2d');
    const labels = Array(MAX_length).fill('0')
    const dataDL = Array(MAX_length).fill(0)
    const dataUL = Array(MAX_length).fill(0)

    Chart.register(centerTextPlugin);
    const chart = new Chart(ctx, {
        type: 'line',
        data: {
            labels,
            datasets: [{
                label: 'DL',
                data: dataDL,
                borderColor: '#40A7EC',
                tension: 0.5,
                pointRadius: 0,
                yAxisID: 'y',
                fill: false,
                borderWidth: 2
            }, {
                label: 'UL',
                data: dataUL,
                borderColor: '#FFA500',
                tension: 0.5,
                pointRadius: 0,
                yAxisID: 'y1',
                fill: false,
                borderWidth: 2
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: {
                duration: ANI_DURATION,    // 0.5秒动画
                easing: 'easeOutQuad'  // 自然缓动效果
            },
            plugins: {
                legend: { display: false },
            },
            scales: {
                x: {
                    grid: { display: false },
                    ticks: { display: false },
                    border: { display: false }
                },
                y: {
                    grid: { display: false },
                    ticks: { display: false },
                    border: { display: false },
                    suggestedMax: 38400,
                    min: 0
                },
                y1: {
                    grid: { display: false },
                    ticks: { display: false },
                    border: { display: false },
                    suggestedMax: 38400,
                    min: 0
                }
            }
        }
    });

    return (value) => {
        if (value != undefined || value != null) {
            setTimeout(() => {
                let UL = window?.UFI_DATA?.realtime_tx_thrpt
                if (UL != undefined) {
                    chart.options.plugins.centerText.text = [
                        {
                            text: `↓ ${formatBytes(value)}/S`
                        },
                        { text: `↑ ${formatBytes(UL)}/S`, color: '#FFA500' }
                    ];

                    let newLabels = [...labels]
                    let newDataDL = [...dataDL]
                    let newDataUL = [...dataUL]

                    newLabels.push(Number(labels[labels.length - 1]) + 1)
                    newDataDL.push(value / 1024)
                    newDataUL.push(UL / 1024)

                    newLabels.length > MAX_length && newLabels.shift()
                    newDataDL.length > MAX_length && newDataDL.shift()
                    newDataUL.length > MAX_length && newDataUL.shift()

                    labels.forEach((_, index) => {
                        labels[index] = newLabels[index]
                    })
                    dataDL.forEach((_, index) => {
                        dataDL[index] = newDataDL[index]
                    })
                    dataUL.forEach((_, index) => {
                        dataUL[index] = newDataUL[index]
                    })

                    chart.update()
                }
            }, 1);
        }
    }
})()