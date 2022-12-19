package bavobbr

import com.theokanning.openai.OpenAiService
import com.theokanning.openai.completion.CompletionChoice
import com.theokanning.openai.completion.CompletionRequest
import forum.ForumService
import forum.model.Credentials
import forum.model.Post
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.net.http.ContentType
import groovyx.net.http.RESTClient
import io.micronaut.context.annotation.Value
import io.micronaut.scheduling.annotation.Scheduled
import jakarta.inject.Singleton

import javax.annotation.PostConstruct
import java.text.SimpleDateFormat

@CompileStatic
@Singleton
@Slf4j
class ForumJob {

    List<Integer> repliedTo = []
    ForumService forumService
    OpenAiService openai
    Integer lastPostId

    @Value('${config.username}')
    String username
    @Value('${config.password}')
    String password
    @Value('${config.vbulletin}')
    String vbulletin
    @Value('${config.openaiKey}')
    String openaiKey
    @Value('${config.callsign}')
    String callsign
    @Value('${config.dalleLambda}')
    String dalleLambda

    @PostConstruct
    def init() {
        def credentials = new Credentials(username: username, password: password)
        forumService = new ForumService(credentials, vbulletin)
        openai = new OpenAiService(openaiKey, 60)
        def myposts = forumService.searchPosts("", null, username, 1, randomDays, false)
        def lastPost = myposts ? myposts.first() : null
        lastPostId = lastPost ? lastPost.postId : -1
        log.info("lastpost id: $lastPostId from $lastPost")
    }

    @Scheduled(fixedDelay = "60s", initialDelay = "10s")
    void executeEveryMinute() {
        log.info("starting a scan action: {}", new SimpleDateFormat("dd/M/yyyy hh:mm:ss").format(new Date()))
        scanAndReply()
    }

    /**
     * search for posts that call the bot and process them one by one by handling request and posting a reply
     */
    void scanAndReply() {
        try {
            def postsToProcess = scanPosts()
            postsToProcess.each { searchPost ->
                log.info("Search result: $searchPost.username $searchPost.date $searchPost.postId")
                def fullPost = forumService.getPost(searchPost.postId)
                // make sure it was not in tags that are not regular text
                if (fullPost.messageText.toLowerCase().contains(callsign)) {
                    // double check we didnt process this yet
                    if (!repliedTo.contains(fullPost.postId)) {
                        reply(searchPost, fullPost)
                        repliedTo << fullPost.postId
                        lastPostId = fullPost.postId
                        log.info("Setting as last Post ID $lastPostId")
                    } else {
                        log.info("Already replied to post $fullPost.postId")
                    }
                } else {
                    log.info("Ignoring post without callsign: $fullPost.postId")
                }
            }
        }
        catch (Exception e) {
            log.info("scan issue $e", e)
        }
    }

    List<Post> scanPosts() {
        def calls = forumService.searchPosts(callsign, null, "", 50, randomDays, false)
        log.info("parsing ${calls.size()} posts that contain '${callsign}'")
        def oldestPostsFirst = calls.sort { it.date }
        def newPosts = oldestPostsFirst.dropWhile { it.postId <= lastPostId }
        log.info("   kept ${newPosts.size()} that were newer than last $lastPostId")
        def newPostsFromOthers = newPosts.findAll { it.username != username && it.username != "Singulariteit" }
        log.info("   of which ${newPostsFromOthers.size()} were form other users")
        return newPostsFromOthers
    }

    void reply(Post searchPost, Post fullPost) {
        log.info("Post: $fullPost.username $fullPost.date $fullPost.postId")
        def message = fullPost.messageText
        println message
        try {
            def callSignStart = message.toLowerCase().indexOf(callsign.toString()) ?: 0
            def callSignEnd = callSignStart + callsign.length()
            def aiRequest = message[callSignEnd..-1].trim()
            handleRequest(aiRequest, searchPost, fullPost)
        }
        catch (Exception e) {
            log.info("handling issue $e", e)
        }
    }


    void handleRequest(String aiRequest, Post searchPost, Post fullPost) {
        if (aiRequest.startsWith("render")) {
            doRender(aiRequest, searchPost, fullPost)
        } else {
            doCompletion(aiRequest, searchPost, fullPost)
        }
    }


    void doCompletion(String aiRequest, Post searchPost, Post fullpost) {
        def reply = doGptRequest(aiRequest)
        log.info("GPT completion is $reply")
        sleep(10000) // we back off on purpose to avoid stress to API via this tool
        if (reply) {
            def out = reply[0].text.trim()
            if (out.size() > 0) {
                def cleaned = clean(out)
                def quoted = asQuote(fullpost.messageText, fullpost.username, fullpost.postId, cleaned)
                forumService.post(searchPost.threadId, quoted)
            }
        }
    }

    void doRender(String aiRequest, Post searchPost, Post fullpost) {
        def imageRequest = aiRequest - "render"
        def reply = doDalleRequest(imageRequest.trim())
        log.info("image reply is $reply")
        sleep(10000) // we back off on purpose to avoid stress to API via this tool
        if (reply) {
            def out = reply.trim()
            if (out.size() > 0) {
                def quoted = asImage(fullpost.messageText, fullpost.username, fullpost.postId, out)
                forumService.post(searchPost.threadId, quoted)
            }
        }
    }


    String doDalleRequest(String prompt) {
        def client = new RESTClient(dalleLambda)
        def response = client.post(path: "/dev/generate",
                requestContentType: ContentType.URLENC,
                body: [prompt: prompt],
                headers: [Accept: 'text/plain'])
        log.info("data: " + response["data"])
        return response["data"]
    }


    List<CompletionChoice> doGptRequest(String text) {
        CompletionRequest completionRequest = CompletionRequest.builder()
                .prompt(text)
                .model("text-davinci-003")
                .echo(false)
                .maxTokens(500)
                .build()
        return openai.createCompletion(completionRequest).getChoices()
    }

    static String asQuote(String source, String user, Integer postId, String reply) {
        return """
[QUOTE=$user;$postId]
$source
[/QUOTE]
$reply
"""
    }

    static String asImage(String source, String user, Integer postId, String reply) {
        return """
[QUOTE=$user;$postId]
$source
[/QUOTE]
[img]$reply[/img]
"""
    }

    static String clean(String text) {
        return text.trim().dropWhile { it == '!' || it == "." || it == "?" }
    }

    private static Integer getRandomDays() {
        return ((Math.random() + 1) * 365) as Integer
    }

}