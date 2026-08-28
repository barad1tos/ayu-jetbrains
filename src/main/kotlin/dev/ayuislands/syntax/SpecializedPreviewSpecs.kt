package dev.ayuislands.syntax

import dev.ayuislands.syntax.PrimitiveCategory.CLASS_DECL
import dev.ayuislands.syntax.PrimitiveCategory.COMMENT
import dev.ayuislands.syntax.PrimitiveCategory.FUNCTION_DECL
import dev.ayuislands.syntax.PrimitiveCategory.INSTANCE_FIELD
import dev.ayuislands.syntax.PrimitiveCategory.KEYWORD
import dev.ayuislands.syntax.PrimitiveCategory.LOCAL_VAR
import dev.ayuislands.syntax.PrimitiveCategory.NUMBER_LITERAL
import dev.ayuislands.syntax.PrimitiveCategory.OPERATOR
import dev.ayuislands.syntax.PrimitiveCategory.PARAMETER
import dev.ayuislands.syntax.PrimitiveCategory.STRING_LITERAL
import dev.ayuislands.syntax.PrimitiveCategory.TYPE_REF

internal object SpecializedPreviewSpecs {
    val entries =
        listOf(
            syntaxPreviewSpec(
                "Bash",
                "preview.sh",
                previewFileType("Shell Script", "sh"),
                "bash.txt",
                FUNCTION_DECL,
                OPERATOR,
                LOCAL_VAR,
                STRING_LITERAL,
            ),
            syntaxPreviewSpec(
                "Cron expression",
                "crontab",
                previewFileType("Cron", "cron"),
                "cron.txt",
                LOCAL_VAR,
                NUMBER_LITERAL,
                OPERATOR,
            ),
            syntaxPreviewSpec(
                "Docker",
                "Dockerfile",
                previewFileType("Dockerfile", "dockerfile"),
                "docker.txt",
                KEYWORD,
                LOCAL_VAR,
                OPERATOR,
            ),
            syntaxPreviewSpec(
                "DQL",
                "preview.dql",
                previewFileType("DQL", "dql"),
                "dql.txt",
                LOCAL_VAR,
            ),
            syntaxPreviewSpec(
                "Drools",
                "preview.drl",
                previewFileType("Drools", "drl"),
                "drools.txt",
                KEYWORD,
                NUMBER_LITERAL,
                STRING_LITERAL,
            ),
            syntaxPreviewSpec(
                "EditorConfig",
                ".editorconfig",
                previewFileType("EditorConfig", "editorconfig"),
                "editorconfig.txt",
                INSTANCE_FIELD,
                STRING_LITERAL,
                LOCAL_VAR,
            ),
            syntaxPreviewSpec(
                "Gherkin",
                "preview.feature",
                previewFileType("Gherkin", "feature"),
                "gherkin.txt",
                KEYWORD,
                PARAMETER,
                OPERATOR,
            ),
            syntaxPreviewSpec(
                "GitLab CI",
                "preview.gitlabciexpression",
                previewFileType("GitLabCiExpression", "gitlabciexpression"),
                "gitlab-ci.txt",
                LOCAL_VAR,
                STRING_LITERAL,
                KEYWORD,
            ),
            syntaxPreviewSpec(
                "HCL",
                "preview.hcl",
                previewFileType("HCL", "hcl"),
                "hcl.txt",
                LOCAL_VAR,
                INSTANCE_FIELD,
            ),
            syntaxPreviewSpec(
                "HTTP client",
                "preview.http",
                previewFileType("HTTP Request", "http"),
                "http-client.txt",
                KEYWORD,
                LOCAL_VAR,
                OPERATOR,
            ),
            syntaxPreviewSpec(
                "Ignore files",
                ".gitignore",
                previewFileType("Ignore", "gitignore"),
                "ignore.txt",
                COMMENT,
                KEYWORD,
                OPERATOR,
                STRING_LITERAL,
            ),
            syntaxPreviewSpec(
                "Makefile",
                "Makefile",
                previewFileType("Makefile", "makefile"),
                "makefile.txt",
                KEYWORD,
                CLASS_DECL,
                FUNCTION_DECL,
                LOCAL_VAR,
                STRING_LITERAL,
                OPERATOR,
                COMMENT,
            ),
            syntaxPreviewSpec(
                "Nginx",
                "nginx.conf",
                previewFileType("Nginx", "conf"),
                "nginx.txt",
                LOCAL_VAR,
                OPERATOR,
                STRING_LITERAL,
                CLASS_DECL,
                KEYWORD,
            ),
            syntaxPreviewSpec(
                "Protobuf",
                "preview.proto",
                previewFileType("protobuf", "proto"),
                "protobuf.txt",
                KEYWORD,
                NUMBER_LITERAL,
                LOCAL_VAR,
                CLASS_DECL,
            ),
            syntaxPreviewSpec(
                "Protobuf text",
                "preview.textproto",
                previewFileType("Protobuf Text", "textproto"),
                "protobuf-text.txt",
                NUMBER_LITERAL,
                LOCAL_VAR,
                CLASS_DECL,
            ).copy(semanticOnlyCategories = setOf(CLASS_DECL)),
            syntaxPreviewSpec(
                "Puppet",
                "preview.pp",
                previewFileType("Puppet", "pp"),
                "puppet.txt",
                STRING_LITERAL,
                CLASS_DECL,
                KEYWORD,
                NUMBER_LITERAL,
                LOCAL_VAR,
            ),
            syntaxPreviewSpec(
                "RegExp",
                "preview.regexp",
                previewFileType("RegExp", "regexp"),
                "regexp.txt",
                STRING_LITERAL,
                CLASS_DECL,
                OPERATOR,
            ),
            syntaxPreviewSpec(
                "TIL",
                previewFixture(
                    "default",
                    "preview.hil",
                    previewFileType(
                        "HIL",
                        "hil",
                        setOf("HIL"),
                    ),
                    "til.txt",
                    KEYWORD,
                    LOCAL_VAR,
                    NUMBER_LITERAL,
                    OPERATOR,
                ),
                previewFixture(
                    "terraform",
                    "preview.tf",
                    previewFileType(
                        "Terraform",
                        "tf",
                        setOf("HCL-Terraform", "Terraform/OpenTofu"),
                    ),
                    "til-terraform.txt",
                    LOCAL_VAR,
                    INSTANCE_FIELD,
                    TYPE_REF,
                ).copy(isDetectionProfile = false),
            ).copy(
                detectionProfiles =
                    listOf(
                        DetectionProfileHint(
                            profileName = "legacy",
                            fileTypeNames = setOf("HCL"),
                            extensions = setOf("tf"),
                        ),
                    ),
            ),
            syntaxPreviewSpec(
                "Windows Batch",
                "preview.bat",
                previewFileType("Batch", "bat"),
                "windows-batch.txt",
                KEYWORD,
                LOCAL_VAR,
                STRING_LITERAL,
                NUMBER_LITERAL,
                COMMENT,
                OPERATOR,
            ),
        )
}
