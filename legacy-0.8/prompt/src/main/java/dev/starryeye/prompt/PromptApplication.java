package dev.starryeye.prompt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PromptApplication {

	public static void main(String[] args) {
		SpringApplication.run(PromptApplication.class, args);

		/**
		 * Prompts serve as the foundation for the language-based inputs that guide an
		 * AI model to produce specific outputs. For those familiar with ChatGPT,
		 * a prompt might seem like merely the next entered into a dialog box that is
		 * sent to the API. However, it encompasses much more than that. In many AI
		 * models, the text for the prompt is not just a simple string.
		 *
		 * Prompt 는 AI 모델이 특정 출력을 생성하도록 안내하는 언어 기반 입력의 기초 역할을 한다.
		 * GPT 기반에 익숙한 우리는 단순 String 처럼 보일 수 있으나, 단순 문자열 이상을 포함한다.
		 *
		 * OpenAI Prompt Engineering
		 * https://platform.openai.com/docs/guides/prompt-engineering
		 *
		 * ChatGPT Prompt Engineering for Developers
		 * https://www.deeplearning.ai/short-courses/chatgpt-prompt-engineering-for-developers/
		 */
	}

}
