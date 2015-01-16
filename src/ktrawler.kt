package org.jetbrains.ktrawler

import org.jetbrains.kotlin.psi.JetTreeVisitorVoid
import retrofit.http.GET
import retrofit.http.Query
import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.cli.jvm.compiler.JetCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.psi.JetClass
import org.jetbrains.kotlin.psi.JetDelegatorByExpressionSpecifier
import retrofit.RestAdapter
import java.io.File
import org.jetbrains.kotlin.psi.JetFunctionLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.psi.JetNamedFunction
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.JetBreakExpression
import org.jetbrains.kotlin.psi.JetLabeledExpression
import org.jetbrains.kotlin.psi.JetContinueExpression
import org.jetbrains.kotlin.psi.JetReturnExpression
import org.jetbrains.kotlin.psi.JetWhenExpression

data class KotlinProject(val rootPath: String)

data class Repo(val full_name: String, val git_url: String)

data class ResultPage(val total_count: Int, val items: List<Repo>)

trait Github {
    GET("/search/repositories")
    fun searchRepositories(Query("q") query: String, Query("page") page: Int, Query("per_page") perPage: Int): ResultPage
}

class GithubRepositoryCollector() {
    val restAdapter = RestAdapter.Builder()
        .setEndpoint("https://api.github.com")
        .build()
    val github = restAdapter.create(javaClass<Github>())

    fun processAllKotlinRepositories(callback: (Repo) -> Boolean) {
        var currentPage = 0
        var totalCount = -1
        var processedCount = 0
        while (totalCount == -1 || processedCount < totalCount) {
            var repos = github.searchRepositories("language:kotlin", currentPage++, 100)
            totalCount = repos.total_count
            processedCount += repos.items.size()
            for (it in repos.items) {
                if (!callback(it)) return
            }
        }
    }
}

class Korpus(val baseDir: String) {
    fun update(callback: (String) -> Boolean) {
        val collector = GithubRepositoryCollector()
        collector.processAllKotlinRepositories {
            cloneOrUpdateRepository(it) && callback(pathTo(it).getPath())
        }
    }

    fun cloneOrUpdateRepository(repo: Repo): Boolean {
        if (pathTo(repo).exists()) {
            updateRepository(repo)
        } else {
            if (!cloneRepository(repo)) return false
        }
        return true
    }

    private fun pathTo(repo: Repo): File = File(baseDir, repo.full_name)

    private fun cloneRepository(repo: Repo): Boolean {
        val owner = repo.full_name.substringBefore('/')
        val ownerDir = File(baseDir, owner)
        ownerDir.mkdirs()
        println("Cloning repo ${repo.full_name}")
        val process = Runtime.getRuntime().exec("git clone ${repo.git_url}", null, ownerDir)
        val result = process.waitFor()
        if (result != 0) {
            println("Repo clone failed: exit code ${result}")
            return false
        }
        return true
    }

    private fun updateRepository(repo: Repo) {
        println("Updating repo ${repo.full_name}")
        val process = Runtime.getRuntime().exec("git pull", null, pathTo(repo))
        val result = process.waitFor()
        if (result != 0) {
            println("Repo update failed: exit code ${result}")
        }
    }
}

data class FeatureUsage(val project: String, val file: String?, val line: Int)

class FeatureUsageCounter(val name: String, val trackUsages: Boolean = false) {
    val usages = arrayListOf<FeatureUsage>()
    var count = 0

    fun increment(projectPath: String, usage: PsiElement) {
        count++
        if (trackUsages) {
            val path = usage.getContainingFile()?.getVirtualFile()?.getPath()
            val doc = PsiDocumentManager.getInstance(usage.getProject()).getDocument(usage.getContainingFile())
            val line = if (doc != null) doc.getLineNumber(usage.getTextRange().getStartOffset()) + 1 else 0
            usages.add(FeatureUsage(projectPath, path?.trimLeading(projectPath), line))
        }
    }

    fun report() {
        println("$name: $count")
        usages.forEach {
            println("  Project: ${it.project}; path: ${it.file}:${it.line}")
        }
    }
}

class Ktrawler(): JetTreeVisitorVoid() {
    var currentRepo: String? = null
    var repositoriesAnalyzed = 0
    var filesAnalyzed = 0
    val syntaxErrors = FeatureUsageCounter("Error count", true)
    val delegationBySpecifiers = FeatureUsageCounter("'by' delegations", true)
    val classes = FeatureUsageCounter("Classes")
    val classObjects = FeatureUsageCounter("Class objects")
    val functions = FeatureUsageCounter("Functions")
    val extensionFunctions = FeatureUsageCounter("Extension functions")
    val extensionFunctionsInClasses = FeatureUsageCounter("Extension functions inside classes")
    val lambdas = FeatureUsageCounter("Lambdas")
    val lambdasWithDeclaredReturnType = FeatureUsageCounter("Lambdas with declared return type", true)
    val labeledExpressions = FeatureUsageCounter("Labeled expressions", true)
    val qualifiedBreakContinue = FeatureUsageCounter("'break' or 'continue' with label", true)
    val returnWithLabel = FeatureUsageCounter("'return' with label", true)
    val whenWithExpression = FeatureUsageCounter("'when' with expression")
    val whenWithoutExpression = FeatureUsageCounter("'when' without expression")

    fun analyzeRepository(rootPath: String) {
        currentRepo = rootPath
        repositoriesAnalyzed++
        val root = object : Disposable {
            override fun dispose() {
            }
        }
        val configuration = CompilerConfiguration()
        configuration.add(CommonConfigurationKeys.SOURCE_ROOTS_KEY, rootPath)
        val environment = JetCoreEnvironment.createForProduction(root, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        environment.getSourceFiles().forEach {
            filesAnalyzed++
            it.acceptChildren(this)
        }
        Disposer.dispose(root)
    }

    fun FeatureUsageCounter.increment(element: PsiElement) = increment(currentRepo!!, element)

    override fun visitErrorElement(element: PsiErrorElement) {
        super.visitErrorElement(element)
        syntaxErrors.increment(element)
    }

    override fun visitClass(klass: JetClass) {
        super.visitClass(klass)
        classes.increment(klass)
        if (klass.getClassObject() != null) {
            classObjects.increment(klass.getClassObject())
        }
    }

    override fun visitNamedFunction(function: JetNamedFunction) {
        super.visitNamedFunction(function)
        functions.increment(function)
        if (function.getReceiverTypeReference() != null) {
            extensionFunctions.increment(function)
            if (PsiTreeUtil.getParentOfType(function, javaClass<JetClass>()) != null) {
                extensionFunctionsInClasses.increment(function)
            }
        }
    }

    override fun visitDelegationByExpressionSpecifier(specifier: JetDelegatorByExpressionSpecifier) {
        super.visitDelegationByExpressionSpecifier(specifier)
        delegationBySpecifiers.increment(specifier);
    }

    override fun visitFunctionLiteralExpression(expression: JetFunctionLiteralExpression) {
        super.visitFunctionLiteralExpression(expression)
        lambdas.increment(expression)
        if (expression.hasDeclaredReturnType()) {
            lambdasWithDeclaredReturnType.increment(expression)
        }
    }

    override fun visitLabeledExpression(expression: JetLabeledExpression) {
        super.visitLabeledExpression(expression)
        labeledExpressions.increment(expression)
    }

    override fun visitBreakExpression(expression: JetBreakExpression) {
        super.visitBreakExpression(expression)
        if (expression.getTargetLabel() != null) {
            qualifiedBreakContinue.increment(expression)
        }
    }

    override fun visitContinueExpression(expression: JetContinueExpression) {
        super.visitContinueExpression(expression)
        if (expression.getTargetLabel() != null) {
            qualifiedBreakContinue.increment(expression)
        }
    }

    override fun visitReturnExpression(expression: JetReturnExpression) {
        super.visitReturnExpression(expression)
        if (expression.getTargetLabel() != null) {
            returnWithLabel.increment(expression)
        }
    }

    override fun visitWhenExpression(expression: JetWhenExpression) {
        super.visitWhenExpression(expression)
        if (expression.getSubjectExpression() != null) {
            whenWithExpression.increment(expression)
        } else {
            whenWithoutExpression.increment(expression)
        }
    }

    fun report() {
        println("Repositories analyzed: $repositoriesAnalyzed")
        println("Files analyzed: $filesAnalyzed")
        syntaxErrors.report()
        delegationBySpecifiers.report()
        classes.report()
        classObjects.report()
        functions.report()
        extensionFunctions.report()
        extensionFunctionsInClasses.report()
        lambdas.report()
        lambdasWithDeclaredReturnType.report()
        labeledExpressions.report()
        qualifiedBreakContinue.report()
        returnWithLabel.report()
        whenWithExpression.report()
        whenWithoutExpression.report()
    }
}

fun main(args: Array<String>) {
    if (args.size() == 0) {
        println("Usage: ktrawler <corpus-directory-path> [<max-repo-count>]")
        return
    }
    val korpus = Korpus(args[0])
    val maxCount = if (args.size() == 2) Integer.parseInt(args[1]) else 1000000
    val ktrawler = Ktrawler()
    var reposProcessed = 0

    val excludedRepos = File("excludedRepos.txt")
    val excludedRepoList = if (excludedRepos.exists())
        excludedRepos.readLines().map { it.trim() }.filter { it.length() > 0 }
    else
        listOf()

    korpus.update() { repoPath ->
        println("Analyzing repository ${repoPath}")
        if (!excludedRepoList.any { repoPath.endsWith("/" + it) }) {
            ktrawler.analyzeRepository(repoPath)
            ++reposProcessed < maxCount
        } else {
            true
        }
    }

    val privateRepo = File("privateRepos.txt")
    if (privateRepo.exists()) {
        privateRepo.readLines().forEach {
            if (it.trim().length() > 0){
                println("Analyzing private repository $it")
                ktrawler.analyzeRepository(it)
            }
        }
    }

    ktrawler.report()
}
