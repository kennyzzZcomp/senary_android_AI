# VS680 SoC阿里多模态大模型能力对接实践

➡️ 查看应用端说明见 [技术说明文档:动画模型](app/README.md)



## 1. 阿里开发平台准备

### 1.1 开发者账号与权限开通
> 提示：首先在阿里云百炼控制台完成账号与权限准备。

<p align="center">
	<a href="https://bailian.console.aliyun.com/?spm=ntm.workbench-commodities-cloud-paydone.0.0.28ae19afdUlZld&tab=app#/app-market/lightApplication" target="_blank">
		<img src="https://img.shields.io/badge/%E6%89%93%E5%BC%80-%E9%98%BF%E9%87%8C%E7%99%BE%E7%82%BC%E5%BA%94%E7%94%A8%E5%B8%82%E5%9C%BA-1f8ceb?logo=alibabacloud&logoColor=white" alt="打开 阿里百炼应用市场" />
	</a>
	<br/>
	<sub>点击按钮打开阿里百炼应用市场</sub>
  
</p>

步骤建议：

1. 登录/注册阿里云账号，确保账号已完成企业或个人实名认证（如需）。
2. 在"应用广场" -> "应用实践" 中定位多模态交互开发套件。
<p align="center">
	<img src="docs/images/aliyun-app-market.png" alt="阿里百炼应用市场入口示意图" width="400" />
	<br/>
	<sub>多模态开发套件入口</sub>
</p>

3. 创建“多模态交互应用”或“语音交互应用”， 选择对应模板（可以随意选一个，进入后可以自行为模型应用配置不同的插件）。
<p align="center">
	<img src="docs/images/model-create.png" alt="阿里模型调用示创建" width="400" />
	<br/>
	<sub>多模态开发套件入口</sub>
</p>

4. 进行模型配置，可以添加不同的应用功能，插件等。配置好后点击发布应用。
<p align="center">
	<img src="docs/images/model-configuration.png" alt="阿里百炼应用模型配置" width="400" />
	<br/>
	<sub>阿里百炼应用模型配置</sub>
</p>


### 1.2 获取 Workspace ID / API Key / App ID

> 目标：明确三项关键信息的来源、记录方式与安全存放位置。

获取路径（以控制台实际页面为准）：

<details>
<summary>📷 点击查看/隐藏图片</summary>
<p align="center">
	<img src="docs/images/workspaceID.png" alt="workspaceid 获取" width="500" />
	<br/>
	<sub>workspaceid 获取</sub>
</p>
</details>

- Workspace ID：进入默认业务空间 -> 业务空间详细 ，复制 Workspace ID。


<details>
<summary>📷 点击查看/隐藏图片</summary>
<p align="center">
	<img src="docs/images/app-id.png" alt="app id获取" width="500" />
	<br/>
	<sub>app id获取</sub>
</p>
</details>

- App ID：在“应用详情”页可见 App ID；如未创建，请先新建应用再获取。


<details>
<summary>📷 点击查看/隐藏图片</summary>
<p align="center">
	<img src="docs/images/api-key.png" alt="api key获取" width="500" />
	<br/>
	<sub>api key获取</sub>
</p>
</details>
- API Key（或 AK/SK/Token）：在“API 接入/访问凭证/密钥管理”等入口创建；注意首次只显示一次明文，请妥善保存。



建议存放（Android 工程，二选一）：

```java
# 选项 A：测试DEMO可直接硬编码在文件中快速测试
private static String workspaceId = "your_workspace_id";
private static String apiKey = "your_app_id";
private static String appId = "your_api_key_or_token";

# 选项 B：local.properties（默认不提交到仓库） <-- TODO 
ALIYUN_WORKSPACE_ID=your_workspace_id
ALIYUN_APP_ID=your_app_id
ALIYUN_API_KEY=your_api_key_or_token
```


## 2. 初期调试开发记录

### 2.1 MCP插件功能
多模态交互开发套件支持三种插件：`推荐插件、插件广场中的插件、自定义插件`。通过智能体应用或Assistant API调用插件后，大模型将根据用户输入的内容、工具名称以及工具描述来判断是否调用插件下的工具。

 - 如果需要调用工具，大模型会选择合适的工具，应用内部完成工具调用后，会将工具返回结果和用户内容合并后再次输入到大模型，由大模型生成最终结果并输出。

 - 如果无需调用工具，大模型将直接生成结果并输出。

 1. 添加阿里推荐插件
 直接在大模型应用控制台进行勾选。
  <p align="center">
	<img src="docs/images/recommand-plugin.png" alt="添加推荐插件" width="400" />
	<br/>
	<sub>如何添加推荐插件</sub>
</p>

 2. 添加自定义插件 
 > 通过 SDK 调用，需要在初始化`MultimodelDialog`时，进入`buildRequestParams`函数，在参数中设置插件中指定的变量值。把`"article_index"`与`"your_plugin_code"`改成自定义插件时设定的参数。

 ```java
 //Java & Android 
HashMap<String, Object> pluginParams = new HashMap<>();
pluginParams.put("article_index",2);
HashMap<String, Object> userDefindParams = new HashMap<>();
userDefindParams.put("your_plugin_code",pluginParams);

MultiModalRequestParam.BizParams bizParams = MultiModalRequestParam.BizParams
   .builder()
   .userDefinedParams(userDefinedParams)
   .build();
 ```

 3. MCP功能

MCP功能需要自行在大模型服务控制台进行添加，添加后无需在代码进行对接，仅需要开启后云端会自行调用MCP服务。简易DEMO如下图所示。MCP会在云端被调用然后联网搜索内容，最后返回结果。目前仅支持接入阿里官方MCP服务。

<table>
  <tr>
    <td align="center">
      <img src="docs/images/mcp_list.png" width="360" />
      <br/>
      <sub>MCP添加列表</sub>
    </td>
    <td align="center">
      <img src="docs/images/mcp_ans.png" width="360" />
      <br/>
      <sub>添加MCP后Agent回复</sub>
    </td>
  </tr>
</table>



### 2.2 添加外挂知识库

1. **如何添加**
 
 访问知识库管理控制台：[阿里百炼知识库管理🎛️](https://bailian.console.aliyun.com/?spm=ntm.workbench-commodities-cloud-paydone.0.0.28ae19afdUlZld&tab=app#/knowledge-base)。随后点击创建知识库，按照步骤把提前准备好的知识库文档添加到里面，等待解析完成。解析完成后即可添加知识库到模型中。
 > 配置知识库具体官方教程：[阿里配置RAG知识库教程🧰](https://www.alibabacloud.com/help/zh/model-studio/rag-knowledge-base)。

2. **检验效果**

 - 在DEMO中添加了SENARY TECH的VS680与ASTRA SDK的相关知识库。添加后可正确回答相关技术数据细节。
 <p align="center">
	<img src="docs/images/knowledge-base.png" alt="添加知识库后回复细节" width="300" />
	<br/>
	<sub>添加知识库后回复效果</sub>
</p>


### 2.3 添加阿里官方百炼应用
儿童故事配有丰富故事库，通过联网搜索和大模型能力补充故事范围。支持用户点播故事内容，可在讲故事中途随时打断，发起故事问答or故事续改写，并在结束问答后无缝衔接原故事进度。

1. **如何添加**
> 进入应用开发 ➡️ 全部应用 ➡️ 多模态交互开发套件 ➡️ 我的应用 ➡️ 配置应用。点击百炼应用中的`+`。

<p align="center">
    <img src="docs/images/add-application.png" alt="如何添加百炼应用" width="300" />
    <br/>
    <sub>如何添加百炼应用</sub>
</p>



2. **检验效果**
故事模式效果展示，需要先对模型说“打开故事模式”。随后模型会调用故事模式agent，同样的模型声音也会改变。
<p align="center">
	<img src="docs/images/story-mode.png" alt="故事模式展示" width="400" />
	<br/>
	<sub>故事模式展示</sub>
</p>


### 2.4 智能语音指令 (设备控制功能)
> 如果需要添加智能语音指令或插件，需要到在我的应用 ➡️ 配置应用 ➡️ 大模型服务控制台进行自行添加。（如下图）
<p align="center">
	<img src="docs/images/voice-instruction-config.png" alt="智能语音指令配置" width="400" />
	<br/>
	<sub>智能语音指令配置示意图</sub>
</p>

 进入编辑指令，查阅/编辑对应的指令参数。可以看到 ```指令名称、指令说明、指令示例、参数名称、下发指令```。具体可以关注并记录“下发指令”的参数名称，也可以进行客制化修改。

 假设需要添加的功能为“音量提高”，则可以添加`INCREASE_DEFAULT_volume`到`executeCommand`也就是命令执行函数中进行具体的实现。

1. **调整音量大小**

对模型说出指令“提高音量”后，可以在使用`Log.d()`在`Logcat`中打印出大模型回传的包内容。
```json
{
	"function":
	{
		"arguments":"{}",
		"name":"INCREASE_DEFAULT_volume"
		},
	"type":"FUNCTION"
}
```
拆分包的内容进行函数功能实现，调用`AudioManager`进行函数实现。函数执行完毕后，需要进行额外两个步骤：更改运行状态以及向服务器回调发送命令执行完毕的反馈。否则程序会卡在“思考中”并且无法恢复后续指令。大致实现思路如下。

```java
case "INCREASE_DEFAULT_volume": {
                        // 固定增加音量
                        final int step = 3;
                        VolumeController.adjustByStep(this, step);
                        // 获取当前音量
                        int cur = VolumeController.getCurrent(this);
                        int max = VolumeController.getMax(this);
                        // DEBUG LOG
                        Log.d(TAG, "音量固定增加(" + step + "): " + cur + "/" + max);
                        // DEBUG SHOW TOAST
                        runOnUiThread(() -> Toast.makeText(this, "音量已增加" + step + "(" + cur + "/" + max + ")", Toast.LENGTH_SHORT).show());
                        // 更改运行状态
                        isExecutingCommand = false;
                        runOnUiThread(()->updateStateUI(currentState));
                        //Log.d(TAG, "isExecutingCommand 设置为 : " + isExecutingCommand + " || " + "currentState: " + currentState);

                        // 向服务器发送命令执行完成的反馈
                        multiModalDialog.requestToRespond("transcript", "音量已增加到 " + cur + "/" + max, null);
                        break;
                    }
```

 - 功能验证：对模型说“音量提高”后，可以成功调节音量大小。

 <p align="center">
	<img src="docs/images/volume-up.png" alt="音量提高示例" width="500" />
	<br/>
	<sub>语音指令：音量提高示例</sub>
</p>


2. **事件提醒功能**

事件提醒功能的开发思路与上述相似。首先添加指令类型，编辑指令内容和形式。收到回调包的指令后需要调用`AlarmManager`进行函数具体功能实现。

```java
    private void executeCommand(String command) {
        Log.d(TAG, "执行命令: " + command);

        try {
            JSONObject commandObj =  new JSONArray(command).getJSONObject(0);
            if (commandObj.has("name")) {
                // multimodal app response
                String cmdName = commandObj.getString("name");
                switch (cmdName) {
                    case "visual_qa":
                        executeVQACommand();
                        break;
                    case "quit_videochat":
                        stopVideoMode();
                        break;
                    case "play_music":
                        handleMusicRadioCommand(commandObj);
                        break;
                    default:
                        executeDefaultCommand();
                        break;
                }
            }else if (commandObj.has("id")) {
                // voice app response
                String cmdId = commandObj.getString("id");
                switch (cmdId) {
                    case "music_radio":
                        handleMusicRadioCommand(commandObj);
                        break;
                    default:
                        break;
                }
            } else if (commandObj.has("function")){
				// 添加新事件处理
			}
		}
	}
```

 - 功能验证：对模型说“10秒钟后提醒我喝水”后，可以成功调用系统闹钟。
  <p align="center">
	<img src="docs/images/event-notice1.png" alt="事件提醒功能示例" width="500" />
	<br/>
	<sub>语音指令：事件提醒功能示例</sub>
</p>


3. **意图识别**
关闭将不再触发调用工具、Agent、联网搜索、对话承接语等基于语义理解的交互功能，适用于低成本、轻量级语音交互场景。

### 2.6 联网搜索功能
>启用此功能前需要在我的应用 ➡️ 配置应用 ➡️ 大模型控制台处开启"联网搜索"选项。

<p align="center">
	<img src="docs/images/online-searching.png" alt="联网搜索功能" width="600" />
	<br/>
	<sub>联网搜索功能示意</sub>
</p>

 - 验证执行操作：对模型发起查询，“帮我搜索南山区附近的咖啡店地址与评分”。
 - 返回结果：模型会询问具体要查询哪些店家，查询后可以正确返回大众点评的实时评分。
 - 返回问题：当模型查询时不会立刻返回结果，需要再次询问。


### 2.7 语音实时对话

1. **语音实时对话：**
<p align="center">
	<img src="docs/images/realtime-dialog.png" alt="语音实时对话示意图" width="600" />
	<br/>
	<sub>语音实时对话示意</sub>
</p>

支持低延迟的流式语音通道，能够在用户说话时进行流式识别并同步生成模型回复的语音输出，适用于交互式语音助手与对话设备场景。常见能力包括唤醒词触发、回声消除与噪声抑制、流式识别（ASR）与流式合成（TTS）、以及边/云端混合部署以保证稳定性与隐私。

2. **开启唤醒词与自动休眠：**

<p align="center">
	<img src="docs/images/wakeup-word.png" alt="语音实时唤醒功能" width="600" />
	<br/>
	<sub>支持唤醒词功能</sub>
</p>

支持语音唤醒功能，默认唤醒词为“小云小云”。如若超过Dialog timeout的时间没有收到说话声音，则需要重新说唤醒词唤醒。

```java
private boolean enableKeywordSpotting = true;
// 启用唤醒，默认唤醒词为"小云小云"
if (enableKeywordSpotting){
	//如果开启唤醒，那么需要先启动录音
	MultiModalDialog.wsUseInternalVAD = true;
	multiModalDialog.enableKWS(true, false);
}
```

如需开启``KWS``也就是key word wake up功能，需要在初始化``MultiModalDialog``的时候使用上述代码。并且把```enableKeywordSpotting```设置为``true``。


### 2.8 长期记忆功能
1. 携带上下文（多轮对话）
 - 短期、会话内的记忆。
 - 存储在单次会话的请求数据中，传递给模型。会话结束或超过轮数后即“遗忘”。
 - 可以自定义几轮会话。

 <p align="center">
	<img src="docs/images/context-round.png" alt="设定上下文示例" width="300" />
	<br/>
	<sub>自定义上下文示例</sub>
</p>

2. 长期记忆
 - 基于对话历史形成专属记忆库，并在后续对话中体现。开启长期记忆后，结合记忆内容进行回复的对话，会增加额外耗时。
 - 长期存在，可以跨越数天、数月甚至更久的不同对话会话。

 ### 2.9 视觉理解功能 - TODO
 视频理解功能支持两种不同的方法进行实现：websocket和RTC。

 1. 使用官方SDK内置的RTC进行视频通话

代码内部配置：需要将`upstream`配置为duplex与AudioAndVideo模式，具体如下。
```java
MultiModalRequestParam.upStream(MultiModalRequestParam.UpStream.builder()
                        .asrPostProcessing(Collections.singletonList(replaceWord))
                        .mode("duplex")
                        .type("AudioAndVideo")
                        .build())
```
接着在初始页面中，把交互链路`ChainMode`设置为RTC。开始通话等待视频投影打开并且通话状态切换到`Listening`后，可以直接进行使用。
<p align="center">
	<img src="docs/images/RTC_voice_chat.png" alt="RTC视频理解示例" width="600" />
	<br/>
	<sub>RTC视频理解示例</sub>
</p>


 2. 使用Websocket自行实现自动拍照上传功能进行通话

 > 使用方法为进入语音通话助手后，说“进入视频通话”，如果要退出则说退出视频通话。

 <table>
  <tr>
    <td align="center">
      <img src="docs/images/video_picture.png" width="300" />
      <br/>
      <sub>视频内容</sub>
    </td>
    <td align="center">
      <img src="docs/images/video_respond.png" width="400" />
      <br/>
      <sub>模型回答</sub>
    </td>
  </tr>
</table>


 ### 2.10 图片问答能力 

图片问答功能在DEMO测试阶段仅为上传图片的URL放入`JSONObject`内，再一同打包为`List<JSONObject>`格式，最后调用`updateParams.setImages(images);`进行上传。
> 在`wbesocket`模式下上传图片后续需要把图片转换为`base64`格式进行上传。

<table>
  <tr>
    <td align="center">
      <img src="docs/images/cat.png" width="300" />
      <br/>
      <sub>图片内容</sub>
    </td>
    <td align="center">
      <img src="docs/images/VQA_Ans.png" width="400" />
      <br/>
      <sub>模型回答</sub>
    </td>
  </tr>
</table>



### TODO LIST:
1. 语音实时对话 ✔
2. 外挂知识库 ✔
3. MCP插件功能 ✔
4. 长期记忆功能 ✔
5. 事件提醒功能 ✔
6. 视觉理解功能 ✔
7. 设备控制功能 ✔
8. 联网搜索功能 ✔
9. 意图识别功能 ✔

---

## 已知问题 / 待修复


- **问题编号**：ISSUE-001
- **标题**：进入故事模式后，无法打断模型讲故事。
- **描述**：当使用“打开故事模式”指令执行音量调整后，agent进入讲故事的模式，讲故事途中无法被打断，不会恢复到聆听状态。讲完故事后，刚才存储的指令会依次放出造成延迟卡顿。
- **复现步骤**：
	1. 唤醒设备并说“打开故事模式”，随后“讲儿童故事”。
	2. 观察 Logcat。
- **期望行为**：进入故事模式讲故事的时候可以在任何时候被打断。
- **实际行为 / 日志片段**：
```
// multiModalDialog.requestToRespond 调用日志
```

- **问题编号**：ISSUE-002
- **标题**：设置时间提醒铃声后，无法中断。
- **描述**：当使用“10秒钟后提醒我喝水”后，铃声响起无法中断，会在后台一直播放系统铃声。
- **复现步骤**：
	1. 唤醒设备并说“10秒钟后提醒我喝水”，随后铃声响起。
	2. 观察 Logcat。
- **期望行为**：铃声响起后可以被手动取消 或者 设定响铃20秒后消除。
- **实际行为 / 日志片段**：
```
// multiModalDialog.requestToRespond 调用日志
```

- **问题编号**：ISSUE-003
- **标题**：电台模式中途退出后无法继续接收语音。
- **描述**：当使用进入音乐电台模式后。
- **复现步骤**：
	1. 唤醒设备并说“进入电台模式”，开始播放音乐。
	2. 退出电台播放，无法进行后续语音指令下放。
	2. 观察 Logcat。
- **期望行为**： 进入电台模式后，可以随时中途推出，并且可以正常继续接受指令。
- **实际行为 / 日志片段**：
```
// multiModalDialog.requestToRespond 调用日志
```




