require "rainbow"

class Greeting
  def initialize(name)
    @name = name
  end

  def to_s
    Rainbow("Hello, #{@name}!").green
  end

  def plain
    Rainbow.uncolor(to_s)
  end
end
